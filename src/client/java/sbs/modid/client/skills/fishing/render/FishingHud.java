/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.fishing.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.skills.fishing.logic.FishingHudVisibility;
import sbs.modid.client.skills.fishing.logic.FishingTracker;
import sbs.modid.client.skills.fishing.model.FishingData;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The fishing tracker HUD: one SBS card stacking the sections the player switched on – sea
 * creatures, catches, shards and the session profit.
 *
 * <p>This is the missing consumer of {@link FishingTracker}: the tracker has always collected the
 * data (chat + inventory diff), but until this class nothing ever drew it, so the toggles in the
 * Fishing settings appeared to do nothing.
 *
 * <p>When the panel is on screen is up to the "Tracker Visibility" setting
 * ({@link FishingHudVisibility}) – rod-or-data by default, rod-only, or permanently. Position and
 * scale ride on {@link HudElement#FISHING_HUD}; the panel opacity is the module's own "HUD Opacity"
 * setting.
 */
public final class FishingHud {

    private static final int PAD = 5;
    private static final int LINE_GAP = 2;
    /** Gap between two sections, on top of the normal line advance. */
    private static final int SECTION_GAP = 3;
    /** The catch list is capped so a long session cannot grow the panel past the screen. */
    private static final int MAX_LOOT_ROWS = 8;
    private static final int MIN_WIDTH = 110;

    private static final int VALUE_COLOR = 0xFFFFD64D; // coin yellow, matches HUD_OVERHEAL
    private static final int COST_COLOR = 0xFFE0605F;  // the SBS "off/negative" red

    /** One rendered row: label left, optional value right, plus its colours. */
    private record Row(String left, String right, int leftColor, int rightColor, boolean header) {

        static Row header(String text) {
            return new Row(text, "", SBSTheme.ACCENT, 0, true);
        }

        static Row of(String left, String right, int rightColor) {
            return new Row(left, right, SBSTheme.TEXT, rightColor, false);
        }

        static Row muted(String text) {
            return new Row(text, "", SBSTheme.TEXT_MUTED, 0, false);
        }
    }

    private FishingHud() {
    }

    private static SBSConfig.FishingSettings cfg() {
        return ConfigManager.getInstance().get().fishing;
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        SBSConfig.FishingSettings cfg = cfg();
        if (!cfg.enabled || HudLayout.isHidden(HudElement.FISHING_HUD)) {
            return;
        }
        if (!cfg.catchTracker && !cfg.shardTracker && !cfg.profitTracker) {
            return;   // the sea creature list is its own panel (SeaCreatureListHud)
        }
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        FishingTracker tracker = FishingTracker.getInstance();
        boolean hasData = !tracker.creatures().isEmpty() || !tracker.loot().isEmpty()
                || !tracker.baitsUsed().isEmpty();
        if (!cfg.hudVisibility().shouldRender(player, hasData)) {
            return;
        }

        List<Row> rows = buildRows(cfg, tracker, false);
        if (rows.isEmpty()) {
            return;
        }
        draw(g, cfg, rows);
    }

    // ------------------------------------------------------------------
    // In-inventory scrollable variant
    // ------------------------------------------------------------------

    /** Scroll offset (rows) of the expanded in-container panel; clamped in {@link #renderInContainer}. */
    private static int containerScroll;
    /** Max scroll reachable given the current rows / screen; set on render, read by the scroll hook. */
    private static int containerMaxScroll;
    /** Whether the in-inventory panel is opened (full scrollable list) vs collapsed (title bar only). */
    private static boolean opened;
    /** The clickable title bar {@code {x, y, w, h}} that toggles open/collapsed, or null when hidden. */
    private static int[] headerRect;
    /** The scrollable list body {@code {x, y, w, h}} (only while opened), for scroll hit-testing. */
    private static int[] bodyRect;

    private static final int HEADER_H = 13;

    /**
     * Drawn over an open container ({@code OverlayRenderMixin}) when {@code expandInInventory} is on:
     * a clickable title bar you press to <b>open</b> the tracker into a full, scrollable list (catch
     * cap lifted, cheapest catches reachable), or collapse it again. A no-op (and clears the hit
     * rects) whenever it should not show.
     */
    public static void renderInContainer(AbstractContainerScreen<?> container, GuiGraphicsExtractor g,
                                         int mouseX, int mouseY) {
        SBSConfig.FishingSettings cfg = cfg();
        Player player = Minecraft.getInstance().player;
        if (player == null || !cfg.enabled || !cfg.expandInInventory
                || HudLayout.isHidden(HudElement.FISHING_HUD)
                || (!cfg.catchTracker && !cfg.shardTracker && !cfg.profitTracker)) {
            headerRect = null;
            bodyRect = null;
            return;
        }
        FishingTracker liveTracker = FishingTracker.getInstance();
        boolean hasData = !liveTracker.creatures().isEmpty() || !liveTracker.loot().isEmpty()
                || !liveTracker.baitsUsed().isEmpty();
        // Only when the free-floating HUD would show too (rod / data / permanent), so it never pops
        // up in an unrelated menu when you are not fishing.
        if (!cfg.hudVisibility().shouldRender(player, hasData)) {
            headerRect = null;
            bodyRect = null;
            return;
        }
        List<Row> rows = buildRows(cfg, liveTracker, true);
        if (rows.isEmpty()) {
            headerRect = null;
            bodyRect = null;
            return;
        }
        Font font = Minecraft.getInstance().font;
        int lineH = font.lineHeight + LINE_GAP;

        int contentW = MIN_WIDTH;
        for (Row row : rows) {
            int w = font.width(row.left()) + (row.right().isEmpty() ? 0 : 8 + font.width(row.right()));
            contentW = Math.max(contentW, w);
        }
        int width = contentW + PAD * 2 + 4;   // +4 for the scrollbar gutter

        HudElement.Bounds b = HudLayout.displayBounds(HudElement.FISHING_HUD, g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());

        int alpha = FishingHudPanel.alpha(cfg.hudOpacity);

        // Title bar – always drawn, click toggles open/collapse. Hover brightens it.
        boolean hoverHeader = mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + HEADER_H;
        FishingHudPanel.panel(g, x, y, width, HEADER_H, alpha, FishingHudPanel.HEADER_BOOST);
        String title = (opened ? "§b▾ " : "§b▸ ") + "Fishing Tracker";
        g.text(font, Component.literal(title), x + PAD, y + (HEADER_H - font.lineHeight) / 2 + 1,
                hoverHeader ? SBSTheme.ACCENT_BRIGHT : SBSTheme.ACCENT, false);
        headerRect = new int[] {x, y, width, HEADER_H};

        if (!opened) {
            bodyRect = null;
            return;
        }

        // Body – the full row list, windowed to the space below the header and scrollable.
        int bodyY = y + HEADER_H + 2;
        int available = g.guiHeight() - bodyY - 6;
        int maxRows = Math.max(3, available / lineH);
        containerMaxScroll = Math.max(0, rows.size() - maxRows);
        containerScroll = clamp(containerScroll, 0, containerMaxScroll);
        int shown = Math.min(rows.size(), maxRows);
        int bodyH = PAD * 2 - LINE_GAP + shown * lineH;

        FishingHudPanel.panel(g, x, bodyY, width, bodyH, alpha, FishingHudPanel.BODY_BOOST);

        int ty = bodyY + PAD;
        for (int i = containerScroll; i < containerScroll + shown && i < rows.size(); i++) {
            Row row = rows.get(i);
            g.text(font, Component.literal(row.left()), x + PAD, ty, row.leftColor(), false);
            if (!row.right().isEmpty()) {
                g.text(font, Component.literal(row.right()),
                        x + width - PAD - 4 - font.width(row.right()), ty, row.rightColor(), false);
            }
            ty += lineH;
        }

        if (containerMaxScroll > 0) {
            int trackX = x + width - 3;
            int trackY = bodyY + 2;
            int trackH = bodyH - 4;
            SciFiRender.roundedRect(g, trackX, trackY, 2, trackH, 1, SBSTheme.CARD_BG_DISABLED);
            int thumbH = Math.max(6, trackH * shown / rows.size());
            int thumbY = trackY + (trackH - thumbH) * containerScroll / containerMaxScroll;
            SciFiRender.roundedRect(g, trackX, thumbY, 2, thumbH, 1, SBSTheme.ACCENT);
        }
        bodyRect = new int[] {x, bodyY, width, bodyH};
    }

    /**
     * Click handler (from {@code ContainerSearchBarMixin}): a click on the title bar toggles the
     * tracker open / collapsed. Returns {@code true} only when it consumed the click.
     */
    public static boolean handleClick(double mouseX, double mouseY) {
        if (headerRect == null) {
            return false;
        }
        if (mouseX >= headerRect[0] && mouseX <= headerRect[0] + headerRect[2]
                && mouseY >= headerRect[1] && mouseY <= headerRect[1] + headerRect[3]) {
            opened = !opened;
            return true;
        }
        return false;
    }

    /**
     * Mouse-wheel handler for the opened in-container list (from {@code ContainerSearchBarMixin}).
     * Returns {@code true} – consuming the scroll – only when opened and the cursor is over the body.
     */
    public static boolean handleScroll(double mouseX, double mouseY, double scrollY) {
        if (!opened || bodyRect == null || containerMaxScroll <= 0 || scrollY == 0) {
            return false;
        }
        if (mouseX < bodyRect[0] || mouseX > bodyRect[0] + bodyRect[2]
                || mouseY < bodyRect[1] || mouseY > bodyRect[1] + bodyRect[3]) {
            return false;
        }
        containerScroll = clamp(containerScroll - (int) Math.signum(scrollY), 0, containerMaxScroll);
        return true;
    }

    // ------------------------------------------------------------------
    // Content
    // ------------------------------------------------------------------

    /**
     * Every visible row, section by section. Sections the player switched off contribute nothing.
     * When {@code expanded} the catch list is uncapped (all items, cheapest included) – used by the
     * scrollable in-inventory panel; the free-floating HUD passes {@code false} and keeps the cap.
     */
    private static List<Row> buildRows(SBSConfig.FishingSettings cfg, FishingTracker tracker, boolean expanded) {
        List<Row> rows = new ArrayList<>();

        // Sea creatures live in their own left-hand list now (SeaCreatureListHud).
        if (cfg.catchTracker) {
            rows.add(Row.header("Catches"));
            // Shards have their own section below - listing them here too would double them up.
            List<Map.Entry<String, Integer>> sorted = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : tracker.loot().entrySet()) {
                if (!(cfg.shardTracker && FishingData.isShard(entry.getKey()))) {
                    sorted.add(entry);
                }
            }
            if (sorted.isEmpty()) {
                rows.add(Row.muted("none yet"));
            } else {
                // Most valuable first (falls back to count when a value is unknown); the cap keeps
                // the free HUD from growing past the screen, and is lifted when expanded.
                sorted.sort((a, c) -> {
                    double va = tracker.valueOf(a.getKey()) * a.getValue();
                    double vc = tracker.valueOf(c.getKey()) * c.getValue();
                    int byValue = Double.compare(vc, va);
                    return byValue != 0 ? byValue : Integer.compare(c.getValue(), a.getValue());
                });
                int shown = expanded ? sorted.size() : Math.min(sorted.size(), MAX_LOOT_ROWS);
                for (int i = 0; i < shown; i++) {
                    Map.Entry<String, Integer> entry = sorted.get(i);
                    rows.add(Row.of(displayName(entry.getKey()),
                            valueSuffix(cfg, entry.getValue(), tracker.valueOf(entry.getKey())),
                            cfg.showValues ? VALUE_COLOR : SBSTheme.TEXT));
                }
                if (sorted.size() > shown) {
                    rows.add(Row.muted("+" + (sorted.size() - shown) + " more (open inventory to scroll)"));
                }
            }
        }

        if (cfg.shardTracker) {
            rows.add(Row.header("Shards"));
            Map<String, Integer> shards = tracker.shards();
            if (shards.isEmpty()) {
                rows.add(Row.muted("none yet"));
            } else {
                for (Map.Entry<String, Integer> entry : shards.entrySet()) {
                    rows.add(Row.of(displayName(entry.getKey()),
                            valueSuffix(cfg, entry.getValue(), tracker.valueOf(entry.getKey())),
                            cfg.showValues ? VALUE_COLOR : SBSTheme.TEXT));
                }
            }
        }

        // Consumed baits are the cost side; their Bazaar value is subtracted from the session. How
        // many baits are LEFT lives in its own movable icon (BaitHud), not here.
        if (cfg.profitTracker && !tracker.baitsUsed().isEmpty()) {
            rows.add(Row.header("Baits Used"));
            for (Map.Entry<String, Integer> entry : tracker.baitsUsed().entrySet()) {
                double cost = tracker.price(entry.getKey()) * entry.getValue();
                rows.add(Row.of(displayName(entry.getKey()) + " ×" + entry.getValue(),
                        "-" + coins(cost), COST_COLOR));
            }
        }
        if (cfg.profitTracker) {
            rows.add(Row.header("Profit"));
            rows.add(Row.of("Times Fished",
                    sbs.modid.client.core.util.NumberDisplay.format(tracker.timesFished()),
                    SBSTheme.TEXT));
            rows.add(Row.of("Session", coins(tracker.totalProfit()), VALUE_COLOR));
            rows.add(Row.of("Per Hour", coins(tracker.profitPerHour()), VALUE_COLOR));
            rows.add(Row.of("Time", duration(tracker.sessionMillis()), SBSTheme.TEXT));
        }
        return rows;
    }

    /** "12" or "12 · 1.2M" depending on Show Values (a zero value stays a plain count). */
    private static String valueSuffix(SBSConfig.FishingSettings cfg, int count, double value) {
        if (!cfg.showValues || value <= 0) {
            return String.valueOf(count);
        }
        return count + " · " + coins(value);
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    private static void draw(GuiGraphicsExtractor g, SBSConfig.FishingSettings cfg, List<Row> rows) {
        Font font = Minecraft.getInstance().font;
        int lineH = font.lineHeight + LINE_GAP;

        // Self-sizing: the card fits its widest row, so long creature names never overflow.
        int contentW = MIN_WIDTH;
        for (Row row : rows) {
            int w = font.width(row.left()) + (row.right().isEmpty() ? 0 : 8 + font.width(row.right()));
            contentW = Math.max(contentW, w);
        }
        int width = contentW + PAD * 2;
        int height = PAD * 2 - LINE_GAP;
        for (Row row : rows) {
            height += lineH + (row.header() ? SECTION_GAP : 0);
        }

        HudElement.Bounds b = HudElement.FISHING_HUD.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());

        int alpha = FishingHudPanel.alpha(cfg.hudOpacity);

        HudLayout.measure(HudElement.FISHING_HUD, x, y, width, height);
        HudLayout.begin(g, HudElement.FISHING_HUD);
        FishingHudPanel.panel(g, x, y, width, height, alpha);

        int ty = y + PAD;
        boolean first = true;
        for (Row row : rows) {
            if (row.header() && !first) {
                ty += SECTION_GAP;
            }
            g.text(font, Component.literal(row.left()), x + PAD, ty, row.leftColor(), false);
            if (!row.right().isEmpty()) {
                g.text(font, Component.literal(row.right()),
                        x + width - PAD - font.width(row.right()), ty, row.rightColor(), false);
            }
            ty += lineH;
            first = false;
        }
        HudLayout.end(g);
    }

    // ------------------------------------------------------------------
    // Formatting
    // ------------------------------------------------------------------

    /** The row label for an item id, with the special ids made readable. */
    private static String displayName(String itemId) {
        if (itemId.startsWith("SHARD_")) {
            return prettify(itemId.substring("SHARD_".length())) + " Shard";
        }
        if ("SKYBLOCK_COIN".equals(itemId)) {
            return "Coins";
        }
        return prettify(itemId);
    }

    /** "SHINY_FISH_SHARD" -> "Shiny Fish Shard". */
    private static String prettify(String itemId) {
        String[] words = itemId.toLowerCase(Locale.ROOT).split("_");
        StringBuilder out = new StringBuilder(itemId.length());
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    /**
     * 1,234 -> "1.2K", 1,200,000 -> "1.2M" – keeps the panel narrow at any coin scale. Signed: a
     * session whose bait cost exceeds its catches reads "-1.2M", not a raw eight-digit number.
     */
    private static String coins(double value) {
        return sbs.modid.client.core.util.NumberDisplay.format(value);
    }

    /** "4m 12s" / "1h 3m" session clock. */
    private static String duration(long millis) {
        long total = millis / 1000;
        if (total < 3600) {
            return (total / 60) + "m " + (total % 60) + "s";
        }
        return (total / 3600) + "h " + ((total % 3600) / 60) + "m";
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
