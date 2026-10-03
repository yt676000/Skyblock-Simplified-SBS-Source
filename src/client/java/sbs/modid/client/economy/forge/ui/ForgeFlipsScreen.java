/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.forge.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.util.NumberDisplay;
import sbs.modid.client.economy.bazaar.ui.FlipFormat;
import sbs.modid.client.economy.forge.logic.ForgeApi;
import sbs.modid.client.economy.forge.logic.ForgeFlipFeed;
import sbs.modid.client.economy.forge.logic.LocalForgeEngine;
import sbs.modid.client.economy.forge.model.LocalForgeFlip;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemIcons;
import sbs.modid.client.ui.component.SciFiButton;
import sbs.modid.client.ui.component.SciFiScrollbar;
import sbs.modid.client.ui.component.SciFiTextField;
import sbs.modid.client.ui.render.DevNotice;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Forge Flips window: every forgeable item ranked by profit per forge hour.
 *
 * <p>The ranking comes from {@link ForgeFlipFeed}, which is either the licence-backed server ranking
 * ({@code /api/forge} knows the recipes, their durations and the day/week price history) or — whenever
 * that cannot be had — the local fallback computed from the bazaar snapshot on this machine. Local
 * results rename the panel and carry a permanent notice above the list saying what is missing.
 *
 * <p>Deliberately the same window as {@link sbs.modid.client.economy.bazaar.ui.BazaarFlipsScreen}:
 * same panel, same row shape, same coin parsing. The two answer the same question about different
 * markets, and looking alike is what makes either of them quick to read.
 *
 * <p>Opened by its hotkey, from the module's button, or over the Forge GUI via
 * {@link ForgeFlipsOverlay}.
 */
public final class ForgeFlipsScreen extends Screen {

    /** Height of one ranking row. Public so a host sizing a scrollbar around the list agrees with it. */
    public static final int ROW_H = 22;

    /** Line height of the notice block drawn above the list. */
    private static final int NOTE_H = 10;

    /** How the in-development notice names this ranking, on the screen and over the Forge GUI. */
    public static final String NOTICE_SUBJECT = "These forge flips";

    /**
     * One drawn entry, whichever ranking produced it. Flattening both shapes into this early keeps
     * the scrolling, hit-testing and drawing from having to know which mode they are in — the mode
     * only decides what the rows say.
     *
     * @param onClick in-window action (the filtered toggle), or {@code null} for a plain row
     * @param dimmed  drawn muted — a filtered-out entry rather than a recommendation
     */
    private record Row(String itemId, String title, String subtitle, String right, double score,
                       List<String> tooltip, Runnable onClick, boolean dimmed) {
    }

    /**
     * Rows for the currently published feed state. Static because {@link ForgeFlipsOverlay} draws the
     * very same list through {@link #drawList} without owning a screen instance — one cache, one
     * shape, no second copy of the table to drift.
     */
    private static List<Row> cachedRows = List.of();
    private static Object builtFor;
    private static boolean builtWithFiltered;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int dividerY;
    private int innerX;
    private int contentWidth;
    private int listTop;
    private int listBottom;
    private int scroll;

    /** The list's scrollbar - the mod's shared one, so it drags like every other SBS list. */
    private final SciFiScrollbar bar = new SciFiScrollbar();

    public ForgeFlipsScreen() {
        super(Component.literal("Forge Flips"));
    }

    @Override
    protected void init() {
        panelW = clamp(this.width - SBSTheme.SCREEN_MARGIN * 2, 420, 700);
        panelH = clamp(this.height - SBSTheme.SCREEN_MARGIN * 2, 260, 460);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        dividerY = panelY + SBSTheme.HEADER_HEIGHT;
        innerX = panelX + SBSTheme.PANEL_PADDING;
        contentWidth = panelW - SBSTheme.PANEL_PADDING * 2;
        int budgetY = dividerY + SBSTheme.GAP_AFTER_HEADER;
        // The notice block is measured at draw time, not here: whether the local disclaimer is part
        // of it changes while the screen is open, and a height captured in init() would be wrong the
        // moment the feed switches mode.
        listTop = budgetY + SBSTheme.ENTRY_HEIGHT + 6;
        int backY = panelY + panelH - SBSTheme.PANEL_PADDING - SBSTheme.SEARCH_HEIGHT;
        listBottom = backY - 6;

        addRenderableOnly(new PanelRenderable());

        // Budget: the same coin field the module settings use, so "50m" means the same everywhere.
        int half = (contentWidth - 6) / 2;
        addRenderableWidget(SciFiTextField.forValueRow(innerX, budgetY, half, SBSTheme.ENTRY_HEIGHT,
                "Budget", "any", 16, 6,
                () -> budgetText(), this::onBudget));
        addRenderableWidget(new SciFiButton(innerX + half + 6, budgetY, contentWidth - half - 6,
                SBSTheme.ENTRY_HEIGHT, Component.literal("Refresh"),
                () -> ForgeFlipFeed.getInstance().request(true)));
        addRenderableWidget(new SciFiButton(innerX, backY, contentWidth, SBSTheme.SEARCH_HEIGHT,
                Component.literal("Back"), () -> Minecraft.getInstance().setScreenAndShow(null)));

        ForgeFlipFeed.getInstance().request(false);
    }

    private static SBSConfig.ForgeSettings cfg() {
        return ConfigManager.getInstance().get().forge;
    }

    /**
     * The budget box's text. Always the short form, whatever "Shorten Numbers" says: this is typed
     * back in, and {@link #parseCoins} reads "50m" but not the grouped "50,000,000".
     */
    private static String budgetText() {
        long budget = cfg().budget;
        return budget > 0 ? NumberDisplay.shorten(budget) : "";
    }

    private void onBudget(String text) {
        long parsed = parseCoins(text);
        if (cfg().budget != parsed) {
            cfg().budget = parsed;
            ConfigManager.getInstance().save();
            ForgeFlipFeed.getInstance().request(true);
        }
    }

    // ------------------------------------------------------------------
    // Rows
    // ------------------------------------------------------------------

    /** The row list for the current feed state, cached until that state or the toggle changes. */
    private static List<Row> rows() {
        ForgeFlipFeed.State state = ForgeFlipFeed.getInstance().state();
        boolean showFiltered = cfg().localShowFiltered;
        if (builtFor != state || builtWithFiltered != showFiltered) {
            cachedRows = build(state, showFiltered);
            builtFor = state;
            builtWithFiltered = showFiltered;
        }
        return cachedRows;
    }

    private static List<Row> build(ForgeFlipFeed.State state, boolean showFiltered) {
        return switch (state.mode()) {
            case REMOTE -> remoteRows(state.remote());
            case LOCAL -> localRows(state.local(), showFiltered);
            case NONE -> List.of();
        };
    }

    private static List<Row> remoteRows(ForgeApi.Response response) {
        List<Row> built = new ArrayList<>();
        if (response == null || response.flips == null) {
            return built;
        }
        for (ForgeApi.Flip flip : response.flips) {
            if (flip == null || flip.item_id == null) {
                continue;
            }
            String sub = "§8craft §7" + NumberDisplay.format(orZero(flip.buy))
                    + " §8→ §7" + NumberDisplay.format(orZero(flip.sell))
                    + " §8(+" + NumberDisplay.format(orZero(flip.profit)) + ")";
            String right = "§a" + NumberDisplay.format(orZero(flip.per_hour)) + "§8/h";
            built.add(new Row(flip.item_id,
                    flip.name == null || flip.name.isBlank() ? flip.item_id : flip.name,
                    sub, right, flip.score == null ? 0 : flip.score, remoteTooltip(flip), null, false));
        }
        return built;
    }

    private static List<String> remoteTooltip(ForgeApi.Flip flip) {
        List<String> tip = new ArrayList<>();
        tip.add(flip.name == null ? flip.item_id : flip.name);
        tip.add("§7Forge time §f" + ForgeFormat.duration(flip.duration_s == null ? 0 : flip.duration_s));
        tip.add("§7Craft §6" + NumberDisplay.format(orZero(flip.buy))
                + " §7→ sell §6" + NumberDisplay.format(orZero(flip.sell)));
        tip.add("§7Profit §a" + NumberDisplay.format(orZero(flip.profit))
                + " §8(" + (flip.margin_pct == null ? 0 : flip.margin_pct) + "%)");
        tip.add("");
        tip.add("§7Per hour now §a" + NumberDisplay.format(orZero(flip.per_hour)));
        if (flip.per_hour_day != null) {
            tip.add("§7Per hour (day avg) §f" + NumberDisplay.format(flip.per_hour_day));
        }
        if (flip.per_hour_week != null) {
            tip.add("§7Per hour (week avg) §f" + NumberDisplay.format(flip.per_hour_week));
        }
        tip.add("§8rating = 50% now + 25% day + 25% week");
        if (flip.basis != null) {
            tip.add("§8based on: " + flip.basis);
        }
        if (flip.hotm != null && !flip.hotm.isBlank()) {
            tip.add("§c" + flip.hotm);
        }
        return tip;
    }

    /**
     * Local rows: the ranking, then the filtered-out entries behind a toggle.
     *
     * <p>Filtered entries stay reachable because the filters are heuristics over one snapshot, not
     * verdicts. Each is labelled with the measured value that tripped it, so disagreeing with the
     * heuristic is a judgement the player can actually make.
     */
    private static List<Row> localRows(LocalForgeEngine.Result result, boolean showFiltered) {
        List<Row> built = new ArrayList<>();
        if (result == null) {
            return built;
        }
        double top = result.kept().isEmpty() ? 0 : result.kept().get(0).profitPerHour();
        for (LocalForgeFlip flip : result.kept()) {
            built.add(localRow(flip, top, null));
        }
        if (!result.filtered().isEmpty()) {
            built.add(new Row(null,
                    "§e" + (showFiltered ? "▾" : "▸") + " Filtered out ("
                            + result.filtered().size() + ")",
                    "§8Excluded by the liquidity and minimum-profit checks - click to "
                            + (showFiltered ? "hide" : "show") + " them with the reason",
                    "", 0, List.of("§7These did not pass one of the local checks.",
                    "§7They are heuristics over a single snapshot, not verdicts -",
                    "§7if you read this market better than they do, judge for yourself.",
                    "", "§8Click to " + (showFiltered ? "hide" : "show")),
                    ForgeFlipsScreen::toggleFiltered, true));
            if (showFiltered) {
                SBSConfig.ForgeSettings settings = cfg();
                for (LocalForgeFlip flip : result.filtered()) {
                    built.add(localRow(flip, top, flip.filterReason(settings.localMinProfit,
                            settings.localMinWeeklyRuns)));
                }
            }
        }
        return built;
    }

    private static Row localRow(LocalForgeFlip flip, double top, String hiddenReason) {
        List<String> tip = new ArrayList<>();
        tip.add(flip.label());
        tip.add("§8Estimated " + ForgeFormat.perHour(flip.profitPerHour())
                + " - a projection, not a rate");
        tip.add("");
        tip.addAll(ForgeFormat.inputLines(flip));
        if (hiddenReason != null) {
            tip.add("");
            tip.add("§cHidden: §7" + hiddenReason);
        }
        String subtitle = hiddenReason != null
                ? "§c" + hiddenReason
                : "§8" + ForgeFormat.runLine(flip) + " §8· " + ForgeFormat.marketLine(flip);
        return new Row(flip.outputId(), flip.label(), subtitle,
                (hiddenReason == null ? "§a" : "§8") + ForgeFormat.perHour(flip.profitPerHour()),
                top <= 0 ? 0 : Math.max(0, Math.min(100, flip.profitPerHour() / top * 100)),
                tip, null, hiddenReason != null);
    }

    private static void toggleFiltered() {
        ConfigManager config = ConfigManager.getInstance();
        config.get().forge.localShowFiltered = !config.get().forge.localShowFiltered;
        config.save();
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        List<Row> list = rows();
        if (scrollY != 0 && !list.isEmpty()) {
            int visible = Math.max(1, (listBottom - listStart()) / ROW_H);
            scroll = clamp(scroll - (int) Math.signum(scrollY), 0, Math.max(0, list.size() - visible));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /** Feeds the shared scrollbar the list's track box; call before rendering or hit-testing it. */
    private void syncBar() {
        int top = listStart();
        bar.set(innerX + contentWidth - SciFiScrollbar.WIDTH, top, listBottom - top,
                rows().size(), Math.max(1, (listBottom - top) / ROW_H));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        syncBar();
        if (bar.handleClick(event.x(), event.y(), scroll, value -> scroll = value)) {
            return true;
        }
        List<Row> list = rows();
        if (event.button() == 0 && !list.isEmpty()) {
            int top = listStart();
            int visible = Math.max(1, (listBottom - top) / ROW_H);
            int start = clamp(scroll, 0, Math.max(0, list.size() - visible));
            int index = start + (int) ((event.y() - top) / ROW_H);
            if (event.x() >= innerX && event.x() < innerX + contentWidth
                    && event.y() >= top && event.y() < listBottom
                    && index >= 0 && index < list.size() && list.get(index).onClick() != null) {
                list.get(index).onClick().run();
                return true;
            }
        }
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        syncBar();
        if (bar.handleDrag(event.y(), value -> scroll = value)) {
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        return bar.release() || super.mouseReleased(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double orZero(Double value) {
        return value == null ? 0 : value;
    }

    // ------------------------------------------------------------------
    // Coins
    // ------------------------------------------------------------------

    /** "50m" / "1.5b" / plain coins -> value; 0 when unparseable (= unlimited). */
    public static long parseCoins(String text) {
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

    public static String formatCoins(double value) {
        return NumberDisplay.format(value);
    }

    /** 30 -> "30s", 1800 -> "30m", 28800 -> "8h" – kept here because other screens call it. */
    public static String formatDuration(int seconds) {
        return ForgeFormat.duration(seconds);
    }

    // ------------------------------------------------------------------
    // The notice block
    // ------------------------------------------------------------------

    /** Where the rows start: below whatever the notice block measured this frame. */
    private int listStart() {
        return listTop + noteLines(font, contentWidth).size() * NOTE_H;
    }

    /**
     * The notice block above the list: the in-development warning always, and the local-results
     * disclaimer on top of it when the ranking did not come from the server.
     *
     * <p>The two say different things and both are needed. "In development" is about the ranking
     * itself being unfinished and holds whichever engine produced it; the local notice is about
     * <i>this</i> result missing price history. A player whose token works still needs the first.
     *
     * <p>Word-wrapped rather than hard-broken, so nothing in it is ever cut off — a disclaimer that
     * only half arrives is worse than none, because the half that survives is the reassuring part.
     */
    private static List<String> noteLines(net.minecraft.client.gui.Font font, int width) {
        if (font == null || width <= 0) {
            return List.of();
        }
        ForgeFlipFeed.State state = ForgeFlipFeed.getInstance().state();
        List<String> lines = new ArrayList<>(DevNotice.lines(font, width, NOTICE_SUBJECT));
        if (!state.isLocal()) {
            return lines;
        }
        lines.add(ForgeFlipFeed.disclaimerTitle());
        lines.addAll(FlipFormat.wrap(font, ForgeFlipFeed.disclaimerBody(state.failure()), width, "§7"));
        LocalForgeEngine.Result result = state.local();
        String scope = result == null ? "" : "  ·  " + result.priced() + " of " + result.recipes()
                + " forge recipes priced";
        lines.addAll(FlipFormat.wrap(font, FlipFormat.dataAge(state.dataTsMs()) + scope, width, "§8"));
        return lines;
    }

    // ------------------------------------------------------------------
    // Rendering – shared with the in-Forge overlay
    // ------------------------------------------------------------------

    /**
     * How many rows the ranking currently has. Public so a host can size a scrollbar around the
     * shared list without owning the response - the same reason {@link #drawList} is public.
     */
    public static int rowCount() {
        return rows().size();
    }

    /**
     * Draws the ranking into an arbitrary rectangle. Static so the overlay over the Forge GUI can
     * reuse it verbatim instead of growing a second, drifting copy of the same table.
     */
    public static void drawList(GuiGraphicsExtractor g, net.minecraft.client.gui.Font font,
                                int x, int y, int w, int bottom, int scroll,
                                int mouseX, int mouseY) {
        ForgeFlipFeed feed = ForgeFlipFeed.getInstance();
        ForgeFlipFeed.State state = feed.state();
        List<Row> list = rows();
        if (list.isEmpty()) {
            g.centeredText(font, Component.literal(emptyText(feed, state)), x + w / 2, y + 8,
                    SBSTheme.TEXT_MUTED);
            return;
        }
        int visible = Math.max(1, (bottom - y) / ROW_H);
        int start = clamp(scroll, 0, Math.max(0, list.size() - visible));
        int rowY = y;
        for (int i = start; i < list.size() && i < start + visible; i++) {
            drawRow(g, font, list.get(i), i + 1, x, rowY, w, mouseX, mouseY);
            rowY += ROW_H;
        }
    }

    /**
     * What an empty list means, which is never simply "no flips". A ranking that could not be built
     * and a ranking that found nothing look identical on screen unless one of them says so.
     */
    private static String emptyText(ForgeFlipFeed feed, ForgeFlipFeed.State state) {
        if (feed.isLoading() || state.mode() == ForgeFlipFeed.Mode.NONE && state.failure() == null) {
            return "§7Loading forge flips...";
        }
        if (state.mode() == ForgeFlipFeed.Mode.LOCAL && state.local() != null
                && state.local().noRecipes()) {
            return "§7The recipe database is still downloading - forge flips need it.";
        }
        if (state.failure() != null && state.mode() == ForgeFlipFeed.Mode.NONE) {
            return "§c" + state.failure().sentence() + " §7No local data either.";
        }
        return "§7Nothing worth forging on this snapshot.";
    }

    private static void drawRow(GuiGraphicsExtractor g, net.minecraft.client.gui.Font font,
                                Row row, int rank, int x, int y, int w, int mouseX, int mouseY) {
        int h = ROW_H - 2;
        boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        SciFiRender.roundedRectWithBorder(g, x, y, w, h, SBSTheme.CORNER_RADIUS,
                hovered ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG, SBSTheme.CARD_BORDER);

        // A score bar behind the row makes the ranking readable without comparing numbers.
        int barW = (int) ((w - 2) * Math.max(0, Math.min(100, row.score())) / 100.0);
        g.fill(x + 1, y + h - 2, x + 1 + barW, y + h - 1, SBSTheme.ACCENT);

        int nameX = x + 22;
        if (row.itemId() != null) {
            ItemStack icon = SkyBlockItemIcons.getInstance().icon(row.itemId(), null, 1);
            g.item(icon.is(Items.BARRIER) ? new ItemStack(Items.ANVIL) : icon, x + 2, y + 2);
        } else {
            nameX = x + 4;
        }

        String right = row.right();
        int rightW = right.isEmpty() ? 0 : font.width(right);
        if (!right.isEmpty()) {
            g.text(font, Component.literal(right), x + w - 4 - rightW, y + 3, SBSTheme.TEXT);
        }
        int nameSpace = (x + w - 8 - rightW) - nameX;
        if (nameSpace > 8) {
            // Only recommendations are numbered: the kept rows are the top of the list, so their
            // index IS their rank, while a filtered row further down would carry a number that reads
            // as "12th best" when it is not in the ranking at all.
            String name = row.itemId() != null && !row.dimmed()
                    ? "§8#" + rank + " §r" + row.title() : row.title();
            g.text(font, Component.literal(trim(font, name, nameSpace)), nameX, y + 3,
                    row.dimmed() ? SBSTheme.TEXT_MUTED
                            : rank <= 3 ? SBSTheme.ACCENT_BRIGHT : SBSTheme.TEXT);
        }
        g.text(font, Component.literal(trim(font, row.subtitle(), w - (nameX - x) - 4)), nameX,
                y + 3 + font.lineHeight, SBSTheme.TEXT_MUTED);

        if (hovered && !row.tooltip().isEmpty()) {
            List<Component> tip = new ArrayList<>(row.tooltip().size());
            for (String line : row.tooltip()) {
                tip.add(Component.literal(line));
            }
            g.setTooltipForNextFrame(font, tip, java.util.Optional.empty(), mouseX, mouseY,
                    SBSTheme.tooltipStyle());
        }
    }

    private static String trim(net.minecraft.client.gui.Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        return font.plainSubstrByWidth(text, Math.max(1, maxWidth - font.width("...")), false) + "...";
    }

    private final class PanelRenderable implements Renderable {
        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            var font = ForgeFlipsScreen.this.font;
            g.fill(0, 0, ForgeFlipsScreen.this.width, ForgeFlipsScreen.this.height, SBSTheme.BG_TINT);
            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);
            int titleY = panelY + (SBSTheme.HEADER_HEIGHT - font.lineHeight) / 2;
            boolean local = ForgeFlipFeed.getInstance().state().isLocal();
            g.centeredText(font, Component.literal(local ? "Forge Flips (local estimate)"
                            : "Forge Flips"), panelX + panelW / 2, titleY,
                    local ? SBSTheme.WARN : SBSTheme.ACCENT_BRIGHT);
            g.fill(panelX + SBSTheme.PANEL_PADDING, dividerY, panelX + panelW - SBSTheme.PANEL_PADDING,
                    dividerY + 1, SBSTheme.ACCENT);

            int noteY = listTop;
            for (String note : noteLines(font, contentWidth)) {
                g.text(font, Component.literal(note), innerX, noteY, SBSTheme.TEXT_MUTED);
                noteY += NOTE_H;
            }
            syncBar();
            int listWidth = contentWidth - (bar.needed() ? SciFiScrollbar.WIDTH + 3 : 0);
            drawList(g, font, innerX, listStart(), listWidth, listBottom, scroll, mouseX, mouseY);
            bar.render(g, scroll, mouseX, mouseY);
        }
    }
}
