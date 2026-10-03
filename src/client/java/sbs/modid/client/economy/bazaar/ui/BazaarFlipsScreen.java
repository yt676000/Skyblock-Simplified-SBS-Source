/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.bazaar.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.util.NumberDisplay;
import sbs.modid.client.economy.bazaar.logic.BazaarFlipFeed;
import sbs.modid.client.economy.bazaar.logic.FlipsApi;
import sbs.modid.client.economy.bazaar.logic.LocalFlipEngine;
import sbs.modid.client.economy.bazaar.model.LocalFlip;
import sbs.modid.client.economy.forge.ui.ForgeFlipsScreen;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemCatalog;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemIcons;
import sbs.modid.client.ui.component.SciFiButton;
import sbs.modid.client.ui.render.DevNotice;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Bazaar Flips window: every bazaar flip the current ranking found, best first.
 *
 * <p>Deliberately the same window as {@link ForgeFlipsScreen} – same panel, same row shape, same
 * scrolling, same coin formatting (reused from it rather than copied, so "50m" keeps meaning the
 * same thing in both). The two screens answer the same question about different markets, and having
 * them look and behave alike is what makes either of them quick to read.
 *
 * <p>Clicking a row runs {@code /bz <item>}, so a flip you like is one click from the order screen.
 * <b>The mod only ever suggests</b> – it opens the page and stops there. It does not place, price,
 * amend or cancel an order, and nothing here sends a click into a Hypixel menu.
 *
 * <p>The ranking itself comes from {@link BazaarFlipFeed}, which is either the licence-backed server
 * ranking or – whenever that cannot be had – the local fallback computed from the bazaar snapshot.
 * Local results carry a permanent notice above the list saying so and saying what is missing; see
 * {@link BazaarFlipFeed#disclaimer}.
 */
public final class BazaarFlipsScreen extends Screen {

    private static final int ROW_H = 22;

    /** Line height of the disclaimer block drawn above the list on local results. */
    private static final int NOTE_H = 10;

    /**
     * One drawn entry, whichever ranking produced it. Flattening both shapes into this early keeps the
     * scrolling, hit-testing and drawing from having to know which mode they are in – the mode only
     * decides what the rows say.
     *
     * @param bzQuery  what to type after {@code /bz}, or {@code null} for a non-flip row
     * @param onClick  in-window action (the filtered toggle); takes precedence over {@code bzQuery}
     * @param dimmed   drawn muted – a filtered-out entry rather than a recommendation
     */
    private record Row(String itemId, String bzQuery, String title, String subtitle, String right,
                       double score, List<String> tooltip, Runnable onClick, boolean dimmed) {
    }

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

    /** Rows for the currently published feed state, rebuilt when that state (or the toggle) changes. */
    private List<Row> rows = List.of();
    private Object builtFor;
    private boolean builtWithFiltered;

    public BazaarFlipsScreen() {
        super(Component.literal("Bazaar Flips"));
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
        int topRowY = dividerY + SBSTheme.GAP_AFTER_HEADER;
        listTop = topRowY + SBSTheme.ENTRY_HEIGHT + 6;
        int backY = panelY + panelH - SBSTheme.PANEL_PADDING - SBSTheme.SEARCH_HEIGHT;
        listBottom = backY - 6;

        addRenderableOnly(new PanelRenderable());
        addRenderableWidget(new SciFiButton(innerX, topRowY, contentWidth, SBSTheme.ENTRY_HEIGHT,
                Component.literal("Refresh"), () -> BazaarFlipFeed.getInstance().request(true)));
        addRenderableWidget(new SciFiButton(innerX, backY, contentWidth, SBSTheme.SEARCH_HEIGHT,
                Component.literal("Back"), () -> Minecraft.getInstance().setScreenAndShow(null)));

        BazaarFlipFeed.getInstance().request(false);
    }

    private static SBSConfig.BazaarSettings cfg() {
        return ConfigManager.getInstance().get().bazaar;
    }

    // ------------------------------------------------------------------ rows

    /** The row list for the current feed state, cached until that state or the toggle changes. */
    private List<Row> rows() {
        BazaarFlipFeed.State state = BazaarFlipFeed.getInstance().state();
        boolean showFiltered = cfg().localFlipShowFiltered;
        if (builtFor != state || builtWithFiltered != showFiltered) {
            rows = build(state, showFiltered);
            builtFor = state;
            builtWithFiltered = showFiltered;
            // Scroll position is deliberately kept across a rebuild. The feed refreshes on its own
            // every minute, and snapping a reader back to the top each time it does would make the
            // list unusable for exactly the person studying it most carefully. It is clamped at draw
            // time instead, so a shorter list still lands somewhere valid.
        }
        return rows;
    }

    private List<Row> build(BazaarFlipFeed.State state, boolean showFiltered) {
        return switch (state.mode()) {
            case REMOTE -> remoteRows(state.remote());
            case LOCAL -> localRows(state, showFiltered);
            case NONE -> List.of();
        };
    }

    private static List<Row> remoteRows(FlipsApi.Response response) {
        List<Row> built = new ArrayList<>();
        if (response == null || response.flips == null) {
            return built;
        }
        for (FlipsApi.Flip flip : response.flips) {
            if (flip == null || flip.item_id == null) {
                continue;
            }
            boolean craft = "craft".equals(flip.type);
            // A craft flip is entered at the BASE book you buy, not the combined result.
            String jumpId = craft && flip.craft_from != null && !flip.craft_from.isBlank()
                    ? flip.craft_from : flip.item_id;
            StringBuilder sub = new StringBuilder("§8buy §7");
            sub.append(ForgeFlipsScreen.formatCoins(orZero(flip.buy)))
                    .append(" §8→ sell §7").append(ForgeFlipsScreen.formatCoins(orZero(flip.sell)));
            if (flip.margin_pct != null) {
                sub.append(" §8· §7").append(Math.round(flip.margin_pct)).append('%');
            }
            if (flip.volume_week != null) {
                sub.append(" §8· §7").append(NumberDisplay.shorten(flip.volume_week)).append("/wk");
            }
            built.add(new Row(flip.item_id, searchName(jumpId),
                    displayName(flip.item_id) + (craft ? " §8[craft]" : ""),
                    sub.toString(), "§a+" + ForgeFlipsScreen.formatCoins(orZero(flip.profit)),
                    flip.score == null ? 0 : flip.score, remoteTooltip(flip, craft), null, false));
        }
        return built;
    }

    private static List<String> remoteTooltip(FlipsApi.Flip flip, boolean craft) {
        List<String> tip = new ArrayList<>();
        tip.add(displayName(flip.item_id));
        if (craft && flip.craft_count != null && flip.craft_from != null) {
            tip.add("§7Combine §f" + flip.craft_count + "x §7" + displayName(flip.craft_from));
        }
        tip.add("§7Buy order §6" + ForgeFlipsScreen.formatCoins(orZero(flip.buy))
                + " §7→ sell offer §6" + ForgeFlipsScreen.formatCoins(orZero(flip.sell)));
        tip.add("§7Profit §a" + ForgeFlipsScreen.formatCoins(orZero(flip.profit))
                + (flip.margin_pct == null ? "" : " §8(" + Math.round(flip.margin_pct) + "%)"));
        if (flip.volume_week != null) {
            tip.add("§7Weekly volume §f" + NumberDisplay.format(flip.volume_week));
        }
        if (flip.fill_s != null && flip.sell_s != null) {
            tip.add("§7One full flip ~§f" + ForgeFlipsScreen.formatDuration(flip.fill_s + flip.sell_s));
        }
        tip.add("");
        tip.add("§8Click to open /bz for it");
        return tip;
    }

    /**
     * Local rows: the ranking, then the filtered-out entries behind a toggle row.
     *
     * <p>Filtered entries stay reachable because the filters are heuristics over one snapshot, not
     * verdicts. Each one is labelled with the measured value that tripped it, so disagreeing with the
     * heuristic is a judgement the user can actually make.
     */
    private static List<Row> localRows(BazaarFlipFeed.State state, boolean showFiltered) {
        SBSConfig.BazaarSettings settings = cfg();
        LocalFlipEngine.Result result = state.local();
        List<Row> built = new ArrayList<>();
        double top = result.kept().isEmpty() ? 0 : result.kept().get(0).profitPerHour();
        for (LocalFlip flip : result.kept()) {
            built.add(localRow(flip, settings, top, null));
        }
        if (!result.filtered().isEmpty()) {
            built.add(new Row(null, null,
                    "§e" + (showFiltered ? "▾" : "▸") + " Filtered out ("
                            + result.filtered().size() + ")",
                    "§8Excluded by the anomaly filters - click to "
                            + (showFiltered ? "hide" : "show") + " them with the reason",
                    "", 0, List.of("§7These did not pass one of the anomaly checks.",
                    "§7They are heuristics over a single snapshot, not verdicts -",
                    "§7if you read this market better than they do, judge for yourself.",
                    "", "§8Click to " + (showFiltered ? "hide" : "show")),
                    BazaarFlipsScreen::toggleFiltered, true));
            if (showFiltered) {
                for (LocalFlip flip : result.filtered()) {
                    built.add(localRow(flip, settings, top, flip.filterReason(
                            settings.localFlipMaxSpreadPct, settings.localFlipMinWeeklyVolume,
                            settings.localFlipMinOrders, settings.localFlipMaxConcentrationPct)));
                }
            }
        }
        return built;
    }

    private static Row localRow(LocalFlip flip, SBSConfig.BazaarSettings settings, double top,
                                String hiddenReason) {
        List<String> tip = new ArrayList<>();
        tip.add(displayName(flip.itemId()));
        tip.add("§8Estimated " + FlipFormat.perHour(flip.profitPerHour())
                + " - a projection, not a rate");
        tip.add("");
        tip.addAll(FlipFormat.inputLines(flip, settings));
        if (hiddenReason != null) {
            tip.add("");
            tip.add("§cHidden: §7" + hiddenReason);
        }
        tip.add("");
        tip.add("§8Click to open /bz for it. The mod never places orders.");
        String subtitle = hiddenReason != null
                ? "§c" + hiddenReason
                : "§8" + FlipFormat.priceLine(flip) + " §8· " + FlipFormat.marketLine(flip);
        return new Row(flip.itemId(), searchName(flip.itemId()),
                displayName(flip.itemId()) + " §8[" + flip.confidence().badge() + "]",
                subtitle,
                (hiddenReason == null ? "§a" : "§8") + FlipFormat.perHour(flip.profitPerHour()),
                top <= 0 ? 0 : Math.max(0, Math.min(100, flip.profitPerHour() / top * 100)),
                tip, null, hiddenReason != null);
    }

    private static void toggleFiltered() {
        ConfigManager config = ConfigManager.getInstance();
        config.get().bazaar.localFlipShowFiltered = !config.get().bazaar.localFlipShowFiltered;
        config.save();
    }

    // ------------------------------------------------------------------ input

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

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubled) {
        List<Row> list = rows();
        if (event.button() == 0 && !list.isEmpty()) {
            int top = listStart();
            int visible = Math.max(1, (listBottom - top) / ROW_H);
            int start = clamp(scroll, 0, Math.max(0, list.size() - visible));
            int index = start + (int) ((event.y() - top) / ROW_H);
            if (event.x() >= innerX && event.x() < innerX + contentWidth
                    && event.y() >= top && event.y() < listBottom
                    && index >= 0 && index < list.size()) {
                Row row = list.get(index);
                if (row.onClick() != null) {
                    row.onClick().run();
                    return true;
                }
                if (row.bzQuery() != null) {
                    openBazaar(row.bzQuery());
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubled);
    }

    /**
     * Runs {@code /bz <name>} – it opens Hypixel's own page for the item and nothing more. Placing the
     * order, choosing the amount and setting the price all stay with the player.
     */
    private static void openBazaar(String query) {
        Minecraft minecraft = Minecraft.getInstance();
        if (query == null || query.isBlank() || minecraft.player == null
                || minecraft.player.connection == null) {
            return;
        }
        minecraft.setScreenAndShow(null);
        minecraft.player.connection.sendCommand("bz " + query);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    /** Where the rows start: below the disclaimer block when one is being drawn. */
    private int listStart() {
        return listTop + noteLines().size() * NOTE_H;
    }

    /**
     * The notice block above the list: the in-development warning always, and the local-results
     * disclaimer on top of it when the ranking did not come from the server.
     *
     * <p>Word-wrapped to the panel rather than hard-broken, so nothing in it is ever cut off — a
     * disclaimer that only half arrives is worse than none, because the half that survives is the
     * reassuring part.
     *
     * <p>The two say different things and both are needed. "In development" is about the ranking
     * itself being unfinished, and holds whichever engine produced it; the local notice is about
     * <i>this</i> result missing price history. A player whose token is working still needs the
     * first one.
     */
    private List<String> noteLines() {
        BazaarFlipFeed.State state = BazaarFlipFeed.getInstance().state();
        if (font == null) {
            return List.of();
        }
        List<String> lines = new ArrayList<>(
                DevNotice.lines(font, contentWidth, "These flip rankings"));
        if (!state.isLocal()) {
            return lines;
        }
        lines.add(BazaarFlipFeed.disclaimerTitle());
        lines.addAll(FlipFormat.wrap(font, BazaarFlipFeed.disclaimerBody(state.failure()),
                contentWidth, "§7"));
        // The data's own timestamp, on every result.
        lines.addAll(FlipFormat.wrap(font, FlipFormat.dataAge(state.dataTsMs()) + "  ·  "
                + state.local().scanned() + " items scanned  ·  "
                + state.local().concurrent() + " flips fit your "
                + LocalFlipEngine.orderSlots(cfg().bazaarFlipperLevel) + " order slots",
                contentWidth, "§8"));
        return lines;
    }

    // ------------------------------------------------------------------ names

    /** The catalogue's display name for an id, falling back to a readable form of the id itself. */
    private static String displayName(String itemId) {
        SkyBlockItemCatalog.Entry entry = SkyBlockItemCatalog.getInstance().byId(itemId);
        if (entry != null && entry.name != null && !entry.name.isBlank()) {
            return entry.name;
        }
        StringBuilder pretty = new StringBuilder();
        for (String word : itemId.toLowerCase(Locale.ROOT).split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (pretty.length() > 0) {
                pretty.append(' ');
            }
            pretty.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return pretty.toString();
    }

    /** What to type after {@code /bz}: the display name without a trailing enchant level. */
    private static String searchName(String itemId) {
        return displayName(itemId).replaceAll("\\s+\\d+$", "");
    }

    // ------------------------------------------------------------------ rendering

    private void drawRows(GuiGraphicsExtractor g, net.minecraft.client.gui.Font font,
                          int mouseX, int mouseY) {
        BazaarFlipFeed feed = BazaarFlipFeed.getInstance();
        BazaarFlipFeed.State state = feed.state();

        // The notice sits above the list and scrolls with nothing: it is always on screen while local
        // results are, which is the whole point of calling it non-dismissable.
        int noteY = listTop;
        for (String note : noteLines()) {
            g.text(font, Component.literal(note), innerX, noteY, SBSTheme.TEXT_MUTED);
            noteY += NOTE_H;
        }

        List<Row> list = rows();
        int top = listStart();
        if (list.isEmpty()) {
            String text = feed.isLoading() ? "§7Loading bazaar flips..."
                    : state.failure() != null
                    ? "§c" + state.failure().sentence() + " §7No local data either."
                    : state.mode() == BazaarFlipFeed.Mode.NONE ? "§7Loading bazaar flips..."
                    : "§7Nothing passed the filters on this snapshot.";
            g.centeredText(font, Component.literal(text), innerX + contentWidth / 2, top + 8,
                    SBSTheme.TEXT_MUTED);
            return;
        }
        int visible = Math.max(1, (listBottom - top) / ROW_H);
        int start = clamp(scroll, 0, Math.max(0, list.size() - visible));
        int rowY = top;
        for (int i = start; i < list.size() && i < start + visible; i++) {
            drawRow(g, font, list.get(i), i + 1, innerX, rowY, contentWidth, mouseX, mouseY);
            rowY += ROW_H;
        }
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
            g.item(icon.is(Items.BARRIER) ? new ItemStack(Items.GOLD_NUGGET) : icon, x + 2, y + 2);
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
            // Only recommendations are numbered. The kept rows occupy the top of the list, so their
            // index IS their rank; a filtered row further down would otherwise carry a rank that reads
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

    private static double orZero(Double value) {
        return value == null ? 0 : value;
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
            var font = BazaarFlipsScreen.this.font;
            g.fill(0, 0, BazaarFlipsScreen.this.width, BazaarFlipsScreen.this.height, SBSTheme.BG_TINT);
            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);
            int titleY = panelY + (SBSTheme.HEADER_HEIGHT - font.lineHeight) / 2;
            boolean local = BazaarFlipFeed.getInstance().state().isLocal();
            g.centeredText(font, Component.literal(local ? "Bazaar Flips (local estimate)"
                            : "Bazaar Flips"), panelX + panelW / 2, titleY,
                    local ? SBSTheme.WARN : SBSTheme.ACCENT_BRIGHT);
            g.fill(panelX + SBSTheme.PANEL_PADDING, dividerY, panelX + panelW - SBSTheme.PANEL_PADDING,
                    dividerY + 1, SBSTheme.ACCENT);
            drawRows(g, font, mouseX, mouseY);
        }
    }
}
