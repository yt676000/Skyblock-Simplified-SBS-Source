/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.bazaar.ui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import sbs.modid.client.economy.bazaar.logic.BazaarFlipFeed;
import sbs.modid.client.economy.bazaar.logic.BazaarOrderTracker;
import sbs.modid.client.economy.bazaar.logic.FlipsApi;
import sbs.modid.client.economy.bazaar.logic.LocalFlipEngine;
import sbs.modid.client.economy.bazaar.model.LocalFlip;
import sbs.modid.client.ui.window.FloatingWindows;
import sbs.modid.client.ui.window.WindowMemory;
import sbs.modid.client.ui.component.SciFiScrollbar;
import sbs.modid.client.ui.render.DevNotice;
import sbs.modid.client.ui.render.LocalRankingNotice;
import sbs.modid.client.ui.render.SourceSwitch;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.render.MenuFrame;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Best Flips window (Bazaar module): a movable, resizable SBS table that opens automatically
 * over Hypixel's Bazaar GUIs while the "Best Flips" toggle is on. Shows the server's current
 * top-N flip ranking – bazaar order-spread flips and book-combine craft flips ("16x Soul Eater 1
 * → Soul Eater 5") – computed and weighted entirely server-side ({@code weights.json → flips});
 * this class only renders the response and refreshes it once a minute.
 *
 * <p>Window mechanics (drag, edge-resize, Ctrl+Scroll zoom, scroll, close) mirror the Similar
 * Auctions window; the layer participates in the {@link FloatingWindows} z-order. Closing via the
 * {@code x} keeps it closed until the Bazaar is reopened.
 */
public final class BestFlipsOverlay {

    private static final BestFlipsOverlay INSTANCE = new BestFlipsOverlay();

    private static final int HEADER_H = 16;
    private static final int ROW_H = 11;
    private static final int PAD = 6;
    private static final int MARGIN = 2;
    private static final int MIN_W = 260;
    private static final int MAX_W = 700;
    private static final int MIN_H = 120;
    private static final int MAX_H = 520;


    /**
     * One drawn row. {@code bz} is the search string for {@code /bz} (a flip row); {@code onClick} is
     * an in-window action such as the filtered-entries toggle. Either makes the row clickable, and a
     * row with neither is plain text.
     */
    private record Line(String left, String right, int leftColor, int rightColor, String bz,
                        Runnable onClick) {
        Line(String left, String right, int leftColor, int rightColor) {
            this(left, right, leftColor, rightColor, null, null);
        }

        Line(String left, String right, int leftColor, int rightColor, String bz) {
            this(left, right, leftColor, rightColor, bz, null);
        }

        boolean clickable() {
            return bz != null || onClick != null;
        }
    }

    private boolean open;
    /** Minimized to a small reopen bar (never closed) – remembered across restarts, like the
     *  Recipe Viewer window. */
    private boolean minimized;
    /** Minimized-bar geometry from the last render, for click hit-testing. */
    private int minBarX;
    private int minBarY;
    private int minBarW;
    private int minBarH;
    private boolean bazaarVisible;

    private List<Line> lines = List.of();
    /** Identity of the feed state the cached {@link #lines} were built from. */
    private Object builtFor;
    /** Rebuild trigger for state that is not the feed's: the filtered-entries toggle. */
    private boolean builtWithFiltered;
    /** Text width the cached lines were wrapped to; resizing the window re-wraps them. */
    private int builtWidth = -1;
    /** The NPC flip ranking the lines were built with (rebuilt when it is). */
    private Object builtNpc;

    private int posX = Integer.MIN_VALUE;
    private int posY = Integer.MIN_VALUE;
    private int sizeW = 340;
    private int sizeH = 300;
    private int panelW = sizeW;
    private int panelH = sizeH;

    private boolean dragging;
    private double grabDX;
    private double grabDY;
    private final sbs.modid.client.ui.window.WindowResizer resizer = new sbs.modid.client.ui.window.WindowResizer();
    private int scrollRow;
    /** Top Y of the first list row, saved each frame so a click can map back to a flip row. */
    private int rowsTop;

    /** The list's scrollbar - the mod's shared one, so it drags like every other SBS list. */
    private final SciFiScrollbar bar = new SciFiScrollbar();

    /** Strip reserved on the right of every row for the scrollbar - see the render pass. */
    private static final int BAR_GUTTER = SciFiScrollbar.WIDTH + 3;
    /** The exact line list drawn last frame, so a click hit-tests against what is on screen. */
    private List<Line> renderedLines = List.of();

    /** Max-budget input box ("50m", "1.5b", plain coins; empty = unlimited). */
    private static final int BUDGET_ROW_H = 15;
    private static final int KEY_ESCAPE = 256;
    private static final int KEY_ENTER = 257;
    private static final int KEY_NUMPAD_ENTER = 335;
    private static final int KEY_BACKSPACE = 259;
    private String budgetText = "";
    private boolean budgetFocused;
    private boolean budgetLoaded;

    /** Where the player left this window: position, size and whether it was collapsed. */
    private final WindowMemory memory = new WindowMemory(FloatingWindows.Layer.FLIPS);

    private BestFlipsOverlay() {
    }

    public static BestFlipsOverlay getInstance() {
        return INSTANCE;
    }

    /** Collapses the window to the small reopen bar (does NOT close it). */
    public void minimize() {
        minimized = true;
        dragging = false;
        budgetFocused = false;
        resizer.end();
        rememberWindow();
    }

    /** Persists position, size and collapsed state so the window comes back where it was left. */
    private void rememberWindow() {
        memory.remember(posX, posY, sizeW, sizeH, minimized);
    }

    // ------------------------------------------------------------------
    // Lifecycle: auto-open over bazaar screens, refresh once a minute
    // ------------------------------------------------------------------

    /** Tracks the screen state and auto-opens/-closes; called every frame from the render pass. */
    private void updateFor(AbstractContainerScreen<?> screen) {
        boolean enabled = sbs.modid.client.core.config.ConfigManager.getInstance().get().bazaar.bestFlips;
        String title = MenuFrame.of(screen).normalised();
        bazaarVisible = enabled && BazaarOrderTracker.isBazaarGui(title);
        if (!bazaarVisible) {
            open = false;
            budgetFocused = false;
            return; // minimized state persists across bazaar re-entries (session), never closes
        }
        if (!open) {
            open = true;
            scrollRow = 0;
            FloatingWindows.raise(FloatingWindows.Layer.FLIPS);
        }
        // Keep the data fresh only while expanded; a collapsed window makes no requests. The feed owns
        // the refresh window and the in-flight guard, so calling it every frame costs two field reads.
        if (!minimized) {
            BazaarFlipFeed.getInstance().request(false);
        }
    }

    // ------------------------------------------------------------------
    // Budget input (persisted, sent to the server as ?budget=)
    // ------------------------------------------------------------------

    /** Loads the persisted budget into the text box once. */
    private void ensureBudgetLoaded() {
        if (budgetLoaded) {
            return;
        }
        budgetLoaded = true;
        long saved = sbs.modid.client.core.config.ConfigManager.getInstance().get().bazaar.flipsBudget;
        // Always the short form: this box is typed back in, and parseBudget reads "50m" but not
        // the grouped "50,000,000".
        budgetText = saved > 0 ? sbs.modid.client.core.util.NumberDisplay.shorten(saved) : "";
    }

    /** Parses "50m" / "1.5b" / "800k" / plain coins; empty or unparsable = 0 (unlimited). */
    private static long parseBudget(String text) {
        String t = text == null ? "" : text.trim().toLowerCase(Locale.ROOT).replace(",", ".");
        if (t.isEmpty()) {
            return 0;
        }
        double mult = 1;
        if (t.endsWith("b")) {
            mult = 1_000_000_000;
            t = t.substring(0, t.length() - 1);
        } else if (t.endsWith("m")) {
            mult = 1_000_000;
            t = t.substring(0, t.length() - 1);
        } else if (t.endsWith("k")) {
            mult = 1_000;
            t = t.substring(0, t.length() - 1);
        }
        try {
            return Math.max(0, Math.round(Double.parseDouble(t) * mult));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Applies the typed budget: persist + immediate refetch when it changed. */
    private void applyBudget() {
        budgetFocused = false;
        long parsed = parseBudget(budgetText);
        budgetText = parsed > 0 ? fmt(parsed) : "";
        var config = sbs.modid.client.core.config.ConfigManager.getInstance();
        if (config.get().bazaar.flipsBudget != parsed) {
            config.get().bazaar.flipsBudget = parsed;
            config.save();
            BazaarFlipFeed.getInstance().request(true);
        }
    }

    /** Consumes every key while the budget box is focused (Enter/Escape apply). */
    public boolean handleKey(net.minecraft.client.input.KeyEvent event) {
        if (!open || !budgetFocused) {
            return false;
        }
        int key = event.key();
        if (key == KEY_ENTER || key == KEY_NUMPAD_ENTER || key == KEY_ESCAPE) {
            applyBudget();
        } else if (key == KEY_BACKSPACE && !budgetText.isEmpty()) {
            budgetText = budgetText.substring(0, budgetText.length() - 1);
        }
        return true; // swallow inventory keybinds (e.g. 'E' = close) while typing
    }

    /** Feeds typed characters into the budget box while it is focused. */
    public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
        if (!open || !budgetFocused) {
            return false;
        }
        String ch = event.codepointAsString().toLowerCase(Locale.ROOT);
        if (budgetText.length() < 10 && ch.matches("[0-9kmb.,]")) {
            budgetText += ch;
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Line building
    // ------------------------------------------------------------------

    /**
     * The rows to draw, rebuilt only when something they depend on changes: the feed's state, the
     * filtered toggle, or the panel width (the disclaimer is word-wrapped to it, and this window is
     * resizable).
     */
    private List<Line> currentLines(Font font, int textWidth) {
        BazaarFlipFeed feed = BazaarFlipFeed.getInstance();
        BazaarFlipFeed.State state = feed.state();
        boolean showFiltered = cfg().localFlipShowFiltered;
        Object npc = cfg().npcFlips && cfg().npcFlipsInBestFlips
                ? sbs.modid.client.economy.npcshop.logic.NpcFlips.ranking() : null;
        if (builtFor != state || builtWithFiltered != showFiltered || builtWidth != textWidth
                || builtNpc != npc) {
            lines = buildLines(state, feed.isLoading(), showFiltered, font, textWidth);
            if (npc != null) {
                List<Line> withNpc = new java.util.ArrayList<>(lines);
                withNpc.addAll(npcLines((sbs.modid.client.economy.npcshop.logic.NpcFlips.Ranking) npc));
                lines = withNpc;
            }
            builtNpc = npc;
            builtFor = state;
            builtWithFiltered = showFiltered;
            builtWidth = textWidth;
        }
        return lines;
    }

    private static sbs.modid.client.core.config.SBSConfig.BazaarSettings cfg() {
        return sbs.modid.client.core.config.ConfigManager.getInstance().get().bazaar;
    }

    /** How many NPC flips the section lists at most - it is a pointer, not a second full ranking. */
    private static final int NPC_ROWS = 8;

    /**
     * The NPC flips section: learned NPC shop offers that sell on the Bazaar for more, after tax.
     * Honest about coverage - it only knows the shops you have opened - and clickable like every
     * other row (the shared {@code BazaarSearch} rule).
     */
    private static List<Line> npcLines(sbs.modid.client.economy.npcshop.logic.NpcFlips.Ranking ranking) {
        List<Line> out = new java.util.ArrayList<>();
        out.add(new Line("", "", SBSTheme.TEXT_MUTED, SBSTheme.TEXT_MUTED));
        out.add(new Line("NPC flips · learned from " + ranking.shops()
                + (ranking.shops() == 1 ? " shop" : " shops"), "", SBSTheme.ACCENT, SBSTheme.TEXT_MUTED));
        if (ranking.shops() == 0) {
            out.add(new Line("  Open an NPC's shop to learn its prices", "", SBSTheme.TEXT_MUTED,
                    SBSTheme.TEXT_MUTED));
            return out;
        }
        if (!ranking.haveBazaar()) {
            out.add(new Line("  Waiting for Bazaar prices", "", SBSTheme.TEXT_MUTED, SBSTheme.TEXT_MUTED));
            return out;
        }
        if (ranking.flips().isEmpty()) {
            out.add(new Line("  Nothing learned flips above your minimums", "", SBSTheme.TEXT_MUTED,
                    SBSTheme.TEXT_MUTED));
        }
        int shown = 0;
        for (sbs.modid.client.economy.npcshop.logic.NpcFlips.Flip flip : ranking.flips()) {
            if (shown++ >= NPC_ROWS) {
                break;
            }
            String name = flip.entry().name == null ? flip.entry().itemId : flip.entry().name;
            String bz = sbs.modid.client.economy.bazaar.logic.BazaarSearch.query(flip.entry().itemId, name);
            out.add(new Line("  " + name + "  · " + flip.entry().npc, "+" + fmt(flip.profitPerUnit()) + " ea",
                    SBSTheme.TEXT, SBSTheme.TOGGLE_ON, bz.isEmpty() ? null : bz, null));
            out.add(new Line("    " + fmt(flip.weekVolume()) + "/wk sold", "", SBSTheme.TEXT_MUTED,
                    SBSTheme.TEXT_MUTED, bz.isEmpty() ? null : bz, null));
        }
        if (ranking.unranked() > 0) {
            out.add(new Line("  " + ranking.unranked() + " learned offer(s) not ranked (item costs or no "
                    + "Bazaar price)", "", SBSTheme.TEXT_MUTED, SBSTheme.TEXT_MUTED));
        }
        return out;
    }

    /** Dispatches on which ranking the feed produced; each mode gets its own honest presentation. */
    private static List<Line> buildLines(BazaarFlipFeed.State state, boolean loading,
                                         boolean showFiltered, Font font, int textWidth) {
        return switch (state.mode()) {
            case REMOTE -> buildRemoteLines(state.remote());
            case LOCAL -> buildLocalLines(state, showFiltered, font, textWidth);
            case NONE -> noDataLines(state, loading, font, textWidth);
        };
    }

    /**
     * Nothing to show. The failure still gets its full sentence rather than a one-word error: the
     * whole point of carrying the cause is that "your token expired" and "we could not reach the
     * server" send the reader to fix different things.
     */
    private static List<Line> noDataLines(BazaarFlipFeed.State state, boolean loading,
                                          Font font, int textWidth) {
        if (loading || state.failure() == null) {
            return List.of(new Line(loading ? "Loading..." : "No data", "",
                    SBSTheme.TEXT_MUTED, SBSTheme.TEXT_MUTED));
        }
        List<Line> built = new ArrayList<>();
        for (String line : FlipFormat.wrap(font, state.failure().sentence(), textWidth, "")) {
            built.add(new Line(line, "", SBSTheme.WARN, SBSTheme.TEXT_MUTED));
        }
        String hint = cfg().localFlipFallback
                ? "No bazaar data cached yet either - it should arrive shortly."
                : "Turn on Local Flip Fallback in the Bazaar settings to compute flips here anyway.";
        for (String line : FlipFormat.wrap(font, hint, textWidth, "")) {
            built.add(new Line(line, "", SBSTheme.TEXT_MUTED, SBSTheme.TEXT_MUTED));
        }
        return built;
    }

    // ------------------------------------------------------------------
    // Local ranking
    // ------------------------------------------------------------------

    /**
     * The fallback view: the disclaimer first and always, then the ranking, then the entries the
     * anomaly filters removed behind a toggle.
     *
     * <p>The disclaimer is drawn as ordinary rows rather than as a dismissable banner, so it scrolls
     * with the content and cannot be turned off. A user who has scrolled past it still sees "local
     * estimate" in the window title.
     */
    private static List<Line> buildLocalLines(BazaarFlipFeed.State state, boolean showFiltered,
                                              Font font, int textWidth) {
        var settings = cfg();
        LocalFlipEngine.Result result = state.local();
        List<Line> built = new ArrayList<>();
        built.add(new Line(BazaarFlipFeed.disclaimerTitle(), "", SBSTheme.WARN, SBSTheme.TEXT_MUTED));
        for (String line : FlipFormat.wrap(font, BazaarFlipFeed.disclaimerBody(state.failure()),
                textWidth, "")) {
            built.add(new Line(line, "", SBSTheme.TEXT_MUTED, SBSTheme.TEXT_MUTED));
        }
        // The data's own timestamp, on every result: a projection off a ten-minute-old book is a
        // ten-minute-old projection however freshly it was drawn.
        for (String line : FlipFormat.wrap(font, FlipFormat.dataAge(result.dataTsMs()) + "  ·  "
                + result.scanned() + " items scanned  ·  " + result.concurrent()
                + " flips fit your " + LocalFlipEngine.orderSlots(settings.bazaarFlipperLevel)
                + " order slots", textWidth, "")) {
            built.add(new Line(line, "", SBSTheme.TEXT_MUTED, SBSTheme.TEXT_MUTED));
        }

        int rank = 0;
        for (LocalFlip flip : result.kept()) {
            rank++;
            addFlipRows(built, flip, "#" + rank, settings,
                    rank <= 3 ? SBSTheme.ACCENT_BRIGHT : SBSTheme.TEXT, null, font, textWidth);
        }
        if (rank == 0) {
            built.add(new Line("Nothing passed the filters on this snapshot.", "",
                    SBSTheme.TEXT_MUTED, SBSTheme.TEXT_MUTED));
        }

        // The filtered list is always computed and always reachable: the heuristics here are proxies
        // for manipulation, not verdicts, and a user who reads the market better than they do should
        // be able to see exactly what was taken away and on what grounds.
        if (!result.filtered().isEmpty()) {
            built.add(new Line("", "", SBSTheme.TEXT_MUTED, SBSTheme.TEXT_MUTED));
            built.add(new Line((showFiltered ? "[-] " : "[+] ") + "Filtered out ("
                    + result.filtered().size() + ") - click to " + (showFiltered ? "hide" : "show"),
                    "", SBSTheme.ACCENT, SBSTheme.TEXT_MUTED, null, BestFlipsOverlay::toggleFiltered));
            if (showFiltered) {
                for (LocalFlip flip : result.filtered()) {
                    addFlipRows(built, flip, "-", settings, SBSTheme.TEXT_MUTED,
                            flip.filterReason(settings.localFlipMaxSpreadPct,
                                    settings.localFlipMinWeeklyVolume, settings.localFlipMinOrders,
                                    settings.localFlipMaxConcentrationPct), font, textWidth);
                }
            }
        }
        return built;
    }

    /**
     * One flip as its headline plus its inputs. The inputs are not an optional extra: the headline is
     * a projection, and the three lines under it are what let a reader decide whether to believe it.
     */
    private static void addFlipRows(List<Line> built, LocalFlip flip, String prefix,
                                    sbs.modid.client.core.config.SBSConfig.BazaarSettings settings,
                                    int nameColor, String hiddenReason, Font font, int textWidth) {
        String bz = bzQuery(flip.itemId());
        built.add(new Line(prefix + " " + displayName(flip.itemId()),
                FlipFormat.perHour(flip.profitPerHour()), nameColor,
                hiddenReason == null ? SBSTheme.TOGGLE_ON : SBSTheme.TEXT_MUTED, bz));
        built.add(new Line("  " + FlipFormat.compactPrices(flip), "",
                SBSTheme.TEXT_MUTED, SBSTheme.TEXT_MUTED, bz));
        built.add(new Line("  " + FlipFormat.compactFlow(flip, settings), "",
                SBSTheme.TEXT_MUTED, SBSTheme.TEXT_MUTED, bz));
        built.add(new Line("  " + FlipFormat.compactMarket(flip), "",
                SBSTheme.TEXT_MUTED, SBSTheme.TEXT_MUTED, bz));
        if (hiddenReason != null) {
            for (String line : FlipFormat.wrap(font, "hidden: " + hiddenReason, textWidth - 8, "")) {
                built.add(new Line("  " + line, "", SBSTheme.WARN, SBSTheme.TEXT_MUTED, bz));
            }
        }
    }

    private static void toggleFiltered() {
        var config = sbs.modid.client.core.config.ConfigManager.getInstance();
        config.get().bazaar.localFlipShowFiltered = !config.get().bazaar.localFlipShowFiltered;
        config.save();
    }

    // ------------------------------------------------------------------
    // Server ranking (unchanged behaviour)
    // ------------------------------------------------------------------

    private static List<Line> buildRemoteLines(FlipsApi.Response r) {
        List<Line> built = new ArrayList<>();
        long age = Math.max(0, System.currentTimeMillis() / 1000L - r.data_ts);
        built.add(new Line("buy = buy order · sell = sell offer · data " + duration(age) + " old",
                "", SBSTheme.TEXT_MUTED, SBSTheme.TEXT_MUTED));
        int rank = 0;
        for (FlipsApi.Flip flip : r.flips) {
            if (flip == null || flip.item_id == null || flip.profit == null) {
                continue;
            }
            rank++;
            boolean craft = "craft".equals(flip.type);
            String name = displayName(flip.item_id);
            String bz = bzQuery(flip);
            built.add(new Line("#" + rank + " " + name + (craft ? "  [craft]" : ""),
                    "+" + fmt(flip.profit),
                    rank <= 3 ? SBSTheme.ACCENT_BRIGHT : SBSTheme.TEXT,
                    SBSTheme.TOGGLE_ON, bz));
            StringBuilder detail = new StringBuilder("   ");
            if (craft && flip.craft_count != null) {
                detail.append(flip.craft_count).append("x ")
                        .append(baseTierName(flip.craft_from)).append(" → ");
            }
            detail.append("buy ").append(fmt(orZero(flip.buy)))
                    .append(" · sell ").append(fmt(orZero(flip.sell)));
            if (flip.margin_pct != null) {
                detail.append(" · ").append(Math.round(flip.margin_pct)).append('%');
            }
            if (flip.volume_week != null) {
                detail.append(" · ").append(flip.volume_week).append("/wk");
            }
            // Estimated time per full flip (order fill + offer selling out).
            if (flip.fill_s != null && flip.sell_s != null) {
                detail.append(" · flip ~").append(duration(flip.fill_s + flip.sell_s));
            }
            built.add(new Line(detail.toString(), "", SBSTheme.TEXT_MUTED, SBSTheme.TEXT_MUTED, bz));
        }
        if (rank == 0) {
            built.add(new Line("No flips passed the server's filters", "",
                    SBSTheme.TEXT_MUTED, SBSTheme.TEXT_MUTED));
        }
        return built;
    }

    /** "ENCHANTMENT_ULTIMATE_REITERATE_5" → "Duplex 5" (shared actual-vs-shown mapping). */
    private static String displayName(String itemId) {
        String id = itemId;
        if (id.startsWith("ENCHANTMENT_")) {
            id = id.substring("ENCHANTMENT_".length());
            int split = id.lastIndexOf('_');
            if (split > 0 && id.substring(split + 1).chars().allMatch(Character::isDigit)) {
                return sbs.modid.client.helper.enchants.EnchantNames.displayName(
                        id.substring(0, split).toLowerCase(Locale.ROOT))
                        + " " + id.substring(split + 1);
            }
        }
        return pretty(id);
    }

    /**
     * The {@code /bz} search string for a flip: the item's display name. Enchant-book products are
     * shown per level ("Duplex 5"), but Hypixel's {@code /bz} searches by name, so the trailing level
     * is dropped there (the search lands on the book, level picked in the menu).
     */
    private static String bzQuery(FlipsApi.Flip flip) {
        return bzQuery(flip.item_id);
    }

    /**
     * Same rule for a bare product id, which is all a locally computed flip carries - the shared one
     * in {@link sbs.modid.client.economy.bazaar.logic.BazaarSearch}, so it cannot drift from the
     * order message's.
     */
    private static String bzQuery(String itemId) {
        if (itemId == null) {
            return "";
        }
        return sbs.modid.client.economy.bazaar.logic.BazaarSearch.query(itemId, displayName(itemId));
    }

    /** Runs {@code /bz <query>} so the clicked flip opens straight in Hypixel's Bazaar search. */
    private static void openBazaar(String query) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && minecraft.player.connection != null) {
            minecraft.player.connection.sendCommand(
                    sbs.modid.client.economy.bazaar.logic.BazaarSearch.command(query));
        }
    }

    /** The craft base's short name ("Tier 1" style): "Soul Eater 1" from its product id. */
    private static String baseTierName(String craftFrom) {
        return craftFrom != null ? displayName(craftFrom) : "base books";
    }

    private static String pretty(String id) {
        String[] words = id.toLowerCase(Locale.ROOT).split("[_\\s]+");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Rendering (FloatingWindows z-order pass)
    // ------------------------------------------------------------------

    public void renderTopMost(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g,
                              int mouseX, int mouseY) {
        updateFor(screen);
        if (!open) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        memory.restore(state -> {
            posX = state.x;
            posY = state.y;
            sizeW = state.width(sizeW);
            sizeH = state.height(sizeH);
            minimized = state.minimized;
        });
        if (posX == Integer.MIN_VALUE) {
            posX = MARGIN + 4;
            posY = (screen.height - sizeH) / 2;
        }
        if (minimized) {
            drawMinimizedBar(screen, g, font, mouseX, mouseY);
            return;
        }
        panelW = clamp(Math.min(sizeW, screen.width - MARGIN * 2), MIN_W, MAX_W);
        panelH = clamp(Math.min(sizeH, screen.height - MARGIN * 2), MIN_H, MAX_H);
        posX = clamp(posX, MARGIN, Math.max(MARGIN, screen.width - panelW - MARGIN));
        posY = clamp(posY, MARGIN, Math.max(MARGIN, screen.height - panelH - MARGIN));

        SciFiRender.glow(g, posX, posY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
        SciFiRender.roundedRect(g, posX, posY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
        SciFiRender.roundedRectGradient(g, posX + 1, posY + 1, panelW - 2, panelH - 2,
                SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

        int textY = posY + (HEADER_H - font.lineHeight) / 2 + 1;
        // The title carries the mode too, so a reader who has scrolled past the disclaimer block still
        // has "these are local estimates" on screen. The in-development tag rides along with it, and
        // is dropped rather than cut when the window is dragged narrow - the room here is whatever the
        // scroll counter and the minimize glyph have left.
        boolean local = BazaarFlipFeed.getInstance().state().isLocal();
        String title = local ? "Best Flips (local)" : "Best Flips";
        g.text(font, Component.literal(DevNotice.tagged(font, title, minimizeGlyphX() - 44 - (posX + PAD))),
                posX + PAD, textY, local ? SBSTheme.WARN : SBSTheme.ACCENT_BRIGHT);
        boolean minHover = inMinimizeBox(mouseX, mouseY);
        g.text(font, Component.literal("-"), minimizeGlyphX() + 4, textY,
                minHover ? SBSTheme.ACCENT_BRIGHT : SBSTheme.TEXT_MUTED);
        g.fill(posX + PAD, posY + HEADER_H, posX + panelW - PAD, posY + HEADER_H + 1, SBSTheme.ACCENT_SOFT);

        // Budget row: label + SBS input box; the value is sent to the server (?budget=...).
        ensureBudgetLoaded();
        int budgetY = posY + HEADER_H + 3;
        g.text(font, Component.literal("Budget:"), posX + PAD, budgetY + 2, SBSTheme.TEXT_MUTED);
        boolean boxHover = inBudgetBox(mouseX, mouseY);
        SciFiRender.roundedRectWithBorder(g, budgetBoxX(), budgetY, budgetBoxW(), 12,
                SBSTheme.CORNER_RADIUS, SBSTheme.SEARCH_FILL,
                budgetFocused ? SBSTheme.ACCENT_BRIGHT
                        : boxHover ? SBSTheme.ACCENT : SBSTheme.CARD_BORDER);
        String shown = budgetFocused
                ? budgetText + ((System.currentTimeMillis() / 500) % 2 == 0 ? "_" : "")
                : (budgetText.isEmpty() ? "unlimited" : budgetText);
        g.text(font, Component.literal(font.plainSubstrByWidth(shown, budgetBoxW() - 8, false)),
                budgetBoxX() + 4, budgetY + 2,
                budgetFocused || !budgetText.isEmpty() ? SBSTheme.TEXT : SBSTheme.TEXT_MUTED);

        // Server / Local, on the same row as the budget and hard right. It is only drawn when it
        // fits beside the budget box: on a window dragged to its minimum the two would overlap, and
        // the same switch is on the Bazaar settings page.
        boolean preferLocal = cfg().flipSource.preferLocal;
        int switchX = switchX(font);
        if (switchX > 0) {
            SourceSwitch.draw(g, font, switchX, budgetY, preferLocal, mouseX, mouseY);
            if (mouseX >= switchX && mouseX < switchX + SourceSwitch.width(font)
                    && mouseY >= budgetY && mouseY < budgetY + SourceSwitch.HEIGHT) {
                List<Component> tip = new ArrayList<>();
                for (String line : SourceSwitch.tooltip(preferLocal)) {
                    tip.add(Component.literal(line));
                }
                g.setTooltipForNextFrame(font, tip, java.util.Optional.empty(), mouseX, mouseY,
                        SBSTheme.tooltipStyle());
            }
        }

        // The offline banner is pinned between the budget row and the list rather than scrolled with
        // it: this window is small and its content scrolls, and a warning that scrolls away is one
        // most readers see once and then forget while reading numbers from the weaker engine. The
        // paragraph explaining what is missing is still in the list below it.
        int noticeWidth = Math.max(1, panelW - PAD * 2);
        int noticeTop = posY + HEADER_H + 4 + BUDGET_ROW_H;
        int noticeHeight = LocalRankingNotice.draw(g, font, posX + PAD, noticeTop, noticeWidth,
                noticeFailure());

        // The bar's strip is reserved whether or not a bar is drawn in it. Reserving it only when
        // needed would loop: a narrower wrap makes more lines, more lines call for a bar, and the bar
        // narrows the wrap again. Seven pixels of unused width beats a layout that oscillates.
        int listWidth = panelW - PAD * 2 - BAR_GUTTER;
        List<Line> list = currentLines(font, listWidth);
        renderedLines = list;
        // Measured off what the banner actually drew, never assumed: it wraps to two lines on a
        // window dragged narrow, and a constant here would paint the first flip over it.
        rowsTop = noticeTop + noticeHeight;
        int rowsBottom = posY + panelH - PAD;
        int visible = Math.max(1, (rowsBottom - rowsTop) / ROW_H);
        int maxScroll = Math.max(0, list.size() - visible);
        scrollRow = clamp(scrollRow, 0, maxScroll);
        if (maxScroll > 0) {
            g.text(font, Component.literal((scrollRow + 1) + "-" + Math.min(list.size(), scrollRow + visible)
                    + "/" + list.size()), minimizeGlyphX() - 40, textY, SBSTheme.TEXT_MUTED);
        }

        for (int i = 0; i < visible && scrollRow + i < list.size(); i++) {
            Line line = list.get(scrollRow + i);
            int rowY = rowsTop + i * ROW_H;
            // Clickable flip rows glow faintly on hover to advertise "click to open /bz".
            boolean clickable = line.clickable();
            if (clickable && mouseX >= posX + PAD && mouseX < posX + PAD + listWidth
                    && mouseY >= rowY - 1 && mouseY < rowY + ROW_H - 1) {
                g.fill(posX + PAD - 2, rowY - 1, posX + PAD + listWidth + 2, rowY + ROW_H - 2,
                        SBSTheme.CARD_BG_HOVER);
            }
            String right = line.right();
            int rightW = right.isEmpty() ? 0 : font.width(right) + 2;
            g.text(font, Component.literal(font.plainSubstrByWidth(line.left(),
                            listWidth - rightW - 4, false)),
                    posX + PAD, rowY, line.leftColor());
            if (!right.isEmpty()) {
                g.text(font, Component.literal(right),
                        posX + PAD + listWidth - font.width(right), rowY, line.rightColor());
            }
        }

        syncBar(list.size(), visible);
        bar.render(g, scrollRow, mouseX, mouseY);

        resizer.renderGrips(g, posX, posY, panelW, panelH, mouseX, mouseY);
    }

    /** The failure to explain in the pinned banner, or {@code null} while the server ranking shows. */
    private static sbs.modid.client.core.api.ApiFailure noticeFailure() {
        BazaarFlipFeed.State state = BazaarFlipFeed.getInstance().state();
        return state.isLocal() ? state.failure() : null;
    }

    /** Feeds the shared scrollbar the list's track box, from what the render pass just measured. */
    private void syncBar(int total, int visible) {
        int rowsBottom = posY + panelH - PAD;
        bar.set(posX + panelW - PAD - SciFiScrollbar.WIDTH, rowsTop, rowsBottom - rowsTop,
                total, visible);
    }

    /** The collapsed state: a small "Best Flips" bar with a restore glyph; click anywhere to reopen. */
    private void drawMinimizedBar(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g,
                                  Font font, int mouseX, int mouseY) {
        minBarW = font.width("Best Flips") + 26;
        minBarH = HEADER_H;
        minBarX = clamp(posX, MARGIN, Math.max(MARGIN, screen.width - minBarW - MARGIN));
        minBarY = clamp(posY, MARGIN, Math.max(MARGIN, screen.height - minBarH - MARGIN));
        boolean hover = mouseX >= minBarX && mouseX < minBarX + minBarW
                && mouseY >= minBarY && mouseY < minBarY + minBarH;
        SciFiRender.roundedRectWithBorder(g, minBarX, minBarY, minBarW, minBarH, SBSTheme.CORNER_RADIUS,
                hover ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG,
                hover ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
        int textY = minBarY + (minBarH - font.lineHeight) / 2 + 1;
        g.text(font, Component.literal("Best Flips"), minBarX + 5, textY, SBSTheme.ACCENT_BRIGHT);
        // Restore glyph (little window icon) on the right.
        int gx = minBarX + minBarW - 12;
        int gy = minBarY + 4;
        int gw = 8;
        int gh = minBarH - 8;
        g.fill(gx, gy, gx + gw, gy + 2, SBSTheme.ACCENT);
        g.outline(gx, gy, gw, gh, hover ? SBSTheme.ACCENT_BRIGHT : SBSTheme.ACCENT);
    }

    // ------------------------------------------------------------------
    // Input (z-order dispatched from ContainerSearchBarMixin)
    // ------------------------------------------------------------------

    public boolean handleClick(AbstractContainerScreen<?> screen, MouseButtonEvent event) {
        if (!open) {
            return false;
        }
        double mx = event.x();
        double my = event.y();
        if (minimized) {
            // Click the collapsed bar to restore; other clicks pass through.
            if (event.button() == 0 && mx >= minBarX && mx < minBarX + minBarW
                    && my >= minBarY && my < minBarY + minBarH) {
                minimized = false;
                rememberWindow();
                FloatingWindows.raise(FloatingWindows.Layer.FLIPS);
                return true;
            }
            return false;
        }
        if (!inPanel(mx, my)) {
            return false;
        }
        if (inMinimizeBox(mx, my) && event.button() == 0) {
            minimize();
            return true;
        }
        if (event.button() == 0 && resizer.begin(mx, my, posX, posY, panelW, panelH)) {
            return true;
        }
        // The source switch, before the budget box: they share a row and a click that reached both
        // would retype the budget as well as change the ranking.
        var font = Minecraft.getInstance().font;
        int switchX = switchX(font);
        if (event.button() == 0 && switchX > 0) {
            Boolean picked = SourceSwitch.hit(font, switchX, posY + HEADER_H + 3, mx, my);
            if (picked != null) {
                if (budgetFocused) {
                    applyBudget();
                }
                sbs.modid.client.core.api.RankingSource.choose(cfg().flipSource, picked);
                BazaarFlipFeed.getInstance().request(true);
                return true;
            }
        }
        // Budget box: click focuses it; any other click inside the panel applies + unfocuses.
        if (event.button() == 0 && inBudgetBox(mx, my)) {
            budgetFocused = true;
            return true;
        }
        if (budgetFocused) {
            applyBudget();
        }
        // The scrollbar before the rows: it is drawn in a strip of its own, but a click that reached
        // both would open a Bazaar page as well as move the list.
        if (event.button() == 0 && bar.handleClick(mx, my, scrollRow, value -> scrollRow = value)) {
            return true;
        }
        // A left click on a flip row opens that item's Bazaar via /bz; an in-window action row (the
        // filtered toggle) runs its own handler instead and takes precedence over the /bz jump.
        if (event.button() == 0 && my >= rowsTop && my < posY + panelH - PAD) {
            int row = scrollRow + (int) ((my - rowsTop) / ROW_H);
            if (row >= 0 && row < renderedLines.size()) {
                Line line = renderedLines.get(row);
                if (line.onClick() != null) {
                    line.onClick().run();
                    return true;
                }
                String bz = line.bz();
                if (bz != null && !bz.isEmpty()) {
                    openBazaar(bz);
                    return true;
                }
            }
        }
        if (my < posY + HEADER_H && event.button() == 0) {
            dragging = true;
            grabDX = mx - posX;
            grabDY = my - posY;
        }
        return true;
    }

    private int budgetBoxX() {
        return posX + PAD + 42;
    }

    private int budgetBoxW() {
        return Math.min(90, panelW - PAD * 2 - 46);
    }

    /**
     * Left edge of the Server/Local switch on the budget row, or {@code -1} when the row is too
     * narrow for it. Measured against the budget box that is actually drawn, so the two can never
     * be painted over each other at a size the player has dragged the window to.
     */
    private int switchX(net.minecraft.client.gui.Font font) {
        int width = SourceSwitch.width(font);
        int x = posX + panelW - PAD - width;
        return x >= budgetBoxX() + budgetBoxW() + 4 ? x : -1;
    }

    private boolean inBudgetBox(double mx, double my) {
        int y = posY + HEADER_H + 3;
        return mx >= budgetBoxX() && mx < budgetBoxX() + budgetBoxW() && my >= y && my < y + 12;
    }

    public boolean handleDrag(AbstractContainerScreen<?> screen, MouseButtonEvent event) {
        if (!open || minimized) {
            return false;
        }
        if (bar.handleDrag(event.y(), value -> scrollRow = value)) {
            return true;
        }
        if (dragging) {
            posX = clamp((int) (event.x() - grabDX), MARGIN, Math.max(MARGIN, screen.width - panelW - MARGIN));
            posY = clamp((int) (event.y() - grabDY), MARGIN, Math.max(MARGIN, screen.height - panelH - MARGIN));
            return true;
        }
        if (resizer.isActive()) {
            int[] rect = resizer.drag(event.x(), event.y(), MIN_W, MAX_W, MIN_H, MAX_H);
            posX = rect[0];
            posY = rect[1];
            sizeW = rect[2];
            sizeH = rect[3];
            return true;
        }
        return false;
    }

    public boolean handleRelease(MouseButtonEvent event) {
        if (!open) {
            return false;
        }
        if (bar.release()) {
            return true;
        }
        boolean wasResizing = resizer.end();
        if (!dragging && !wasResizing) {
            return false;
        }
        dragging = false;
        rememberWindow();
        return true;
    }

    public boolean handleScroll(AbstractContainerScreen<?> screen, double mouseX, double mouseY, double scrollY) {
        if (!open || minimized || !inPanel(mouseX, mouseY)) {
            return false;
        }
        if (isCtrlDown()) {
            int step = (int) Math.signum(scrollY);
            sizeW = clamp(sizeW + step * 30, MIN_W, MAX_W);
            sizeH = clamp(sizeH + step * 20, MIN_H, MAX_H);
            rememberWindow();
            return true;
        }
        scrollRow = Math.max(0, scrollRow - (int) Math.signum(scrollY));
        return true;
    }

    // ------------------------------------------------------------------
    // Geometry / formatting helpers
    // ------------------------------------------------------------------

    private int minimizeGlyphX() {
        return posX + panelW - 14;
    }

    private boolean inMinimizeBox(double mx, double my) {
        return mx >= minimizeGlyphX() && mx < minimizeGlyphX() + 12 && my >= posY + 2 && my < posY + HEADER_H;
    }

    private boolean inPanel(double mx, double my) {
        return mx >= posX && mx < posX + panelW && my >= posY && my < posY + panelH;
    }

    private static boolean isCtrlDown() {
        var window = Minecraft.getInstance().getWindow();
        return InputConstants.isKeyDown(window, InputConstants.KEY_LCONTROL)
                || InputConstants.isKeyDown(window, InputConstants.KEY_RCONTROL);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double orZero(Double value) {
        return value != null ? value : 0;
    }

    /** Compact coin format: 1.2B / 34.5M / 850K / 123, honouring "Shorten Numbers". */
    private static String fmt(double value) {
        return sbs.modid.client.core.util.NumberDisplay.format(value);
    }

    /** "45s" / "12m" / "3h 05m" / "2d 4h". */
    private static String duration(long seconds) {
        if (seconds < 60) {
            return seconds + "s";
        }
        if (seconds < 3600) {
            return (seconds / 60) + "m";
        }
        if (seconds < 86400) {
            return String.format(Locale.ROOT, "%dh %02dm", seconds / 3600, (seconds % 3600) / 60);
        }
        return String.format(Locale.ROOT, "%dd %dh", seconds / 86400, (seconds % 86400) / 3600);
    }
}
