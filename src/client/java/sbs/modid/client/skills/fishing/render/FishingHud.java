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
import sbs.modid.client.core.render.CachedModel;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemCatalog;
import sbs.modid.client.skills.fishing.logic.FishingCardLayout;
import sbs.modid.client.skills.fishing.logic.FishingHudVisibility;
import sbs.modid.client.skills.fishing.logic.FishingTracker;
import sbs.modid.client.skills.fishing.logic.ReelTimer;
import sbs.modid.client.skills.fishing.model.FishingCardSection;
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
 * The fishing tracker HUD: one SBS card stacking the sections the player switched on – catches,
 * rare drops, shards, the reel timer, the baits used and the session profit – in the order the
 * player arranged them ({@link FishingCardLayout}, edited with the shared card section editor).
 *
 * <p>This is the missing consumer of {@link FishingTracker}: the tracker has always collected the
 * data (chat + inventory diff), but until this class nothing ever drew it, so the toggles in the
 * Fishing settings appeared to do nothing.
 *
 * <p>When the panel is on screen is up to the "Tracker Visibility" setting
 * ({@link FishingHudVisibility}) – rod-or-data by default, rod-only, or permanently. Position and
 * scale ride on {@link HudElement#FISHING_HUD}; the panel opacity is the module's own "HUD Opacity"
 * setting.
 *
 * <p><b>Cost.</b> The rows, their components and the card size are a {@link CachedModel} keyed on
 * the config revision, the tracker revision, the wall-clock second (prices, session time) and the
 * Reel Timer's displayed tenth of a second. A frame whose key is unchanged only draws; the rows are
 * rebuilt at most ten times a second while a cast is waiting, and once a second otherwise.
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

    /** One rendered row: label left, optional value right, plus its colours; built once per change. */
    private record Row(Component left, Component right, int leftWidth, int rightWidth,
                       int leftColor, int rightColor, boolean header) {

        static Row make(Font font, String left, String right, int leftColor, int rightColor, boolean header) {
            return new Row(Component.literal(left), right.isEmpty() ? null : Component.literal(right),
                    font.width(left), right.isEmpty() ? 0 : font.width(right), leftColor, rightColor, header);
        }
    }

    /** The prepared card: rows plus the size they need. */
    private record Model(List<Row> rows, int contentWidth, int height) {
    }

    /** Everything the rows are built from. Rebuilt only when one of these values changes. */
    private record Key(int configRevision, int trackerRevision, long wallSecond, long reelTenth,
                       int reelRevision, boolean expanded) {
    }

    private static final CachedModel<Key, Model> HUD_MODEL =
            new CachedModel<>("hud.FishingHud", FishingHud::build);
    private static final CachedModel<Key, Model> CONTAINER_MODEL =
            new CachedModel<>("hud.FishingHud.container", FishingHud::build);
    /** The last key handed to each cache, so an unchanged frame allocates no new key. */
    private static Key lastHudKey;
    private static Key lastContainerKey;

    private FishingHud() {
    }

    private static SBSConfig.FishingSettings cfg() {
        return ConfigManager.getInstance().get().fishing;
    }

    private static boolean hasData(FishingTracker tracker) {
        return !tracker.creatures().isEmpty() || !tracker.loot().isEmpty()
                || !tracker.rareDrops().isEmpty() || !tracker.baitsUsed().isEmpty();
    }

    private static boolean anySection(SBSConfig.FishingSettings cfg) {
        for (FishingCardSection section : FishingCardSection.values()) {
            if (FishingCardLayout.enabled(cfg, section)) {
                return true;
            }
        }
        return false;
    }

    /** The key for this frame: the previous object when nothing changed, a new one otherwise. */
    private static Key keyFor(Key last, SBSConfig.FishingSettings cfg, FishingTracker tracker, boolean expanded) {
        long now = System.currentTimeMillis();
        int configRevision = ConfigManager.getInstance().revision();
        int trackerRevision = tracker.revision();
        long second = now / 1000;
        ReelTimer reel = tracker.reelTimer();
        long wait = cfg.reelTimer ? reel.currentWait(now) : -1;
        long tenth = wait < 0 ? -1 : wait / 100;
        int reelRevision = reel.revision();
        if (last != null && last.configRevision() == configRevision
                && last.trackerRevision() == trackerRevision && last.wallSecond() == second
                && last.reelTenth() == tenth && last.reelRevision() == reelRevision) {
            return last;
        }
        return new Key(configRevision, trackerRevision, second, tenth, reelRevision, expanded);
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        SBSConfig.FishingSettings cfg = cfg();
        if (!cfg.enabled || HudLayout.isHidden(HudElement.FISHING_HUD) || !anySection(cfg)) {
            return;   // the sea creature list is its own panel (SeaCreatureListHud)
        }
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        FishingTracker tracker = FishingTracker.getInstance();
        if (!cfg.hudVisibility().shouldRender(player, hasData(tracker))) {
            return;
        }
        lastHudKey = keyFor(lastHudKey, cfg, tracker, false);
        Model model = HUD_MODEL.get(lastHudKey);
        if (model.rows().isEmpty()) {
            return;
        }
        draw(g, cfg, model);
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
    // render thread only: the two hit rects are rewritten in place instead of reallocated per frame
    private static final int[] HEADER_SCRATCH = new int[4];
    private static final int[] BODY_SCRATCH = new int[4];

    private static final int HEADER_H = 13;
    private static final Component TITLE_OPEN = Component.literal("§b▾ Fishing Tracker");
    private static final Component TITLE_CLOSED = Component.literal("§b▸ Fishing Tracker");

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
                || HudLayout.isHidden(HudElement.FISHING_HUD) || !anySection(cfg)) {
            headerRect = null;
            bodyRect = null;
            return;
        }
        FishingTracker liveTracker = FishingTracker.getInstance();
        // Only when the free-floating HUD would show too (rod / data / permanent), so it never pops
        // up in an unrelated menu when you are not fishing.
        if (!cfg.hudVisibility().shouldRender(player, hasData(liveTracker))) {
            headerRect = null;
            bodyRect = null;
            return;
        }
        lastContainerKey = keyFor(lastContainerKey, cfg, liveTracker, true);
        Model model = CONTAINER_MODEL.get(lastContainerKey);
        List<Row> rows = model.rows();
        if (rows.isEmpty()) {
            headerRect = null;
            bodyRect = null;
            return;
        }
        Font font = Minecraft.getInstance().font;
        int lineH = font.lineHeight + LINE_GAP;
        int width = model.contentWidth() + PAD * 2 + 4;   // +4 for the scrollbar gutter

        HudElement.Bounds b = HudLayout.displayBounds(HudElement.FISHING_HUD, g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());

        int alpha = FishingHudPanel.alpha(cfg.hudOpacity);

        // Title bar – always drawn, click toggles open/collapse. Hover brightens it.
        boolean hoverHeader = mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + HEADER_H;
        FishingHudPanel.panel(g, x, y, width, HEADER_H, alpha, FishingHudPanel.HEADER_BOOST);
        g.text(font, opened ? TITLE_OPEN : TITLE_CLOSED, x + PAD, y + (HEADER_H - font.lineHeight) / 2 + 1,
                hoverHeader ? SBSTheme.ACCENT_BRIGHT : SBSTheme.ACCENT, false);
        headerRect = rect(HEADER_SCRATCH, x, y, width, HEADER_H);

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
            g.text(font, row.left(), x + PAD, ty, row.leftColor(), false);
            if (row.right() != null) {
                g.text(font, row.right(), x + width - PAD - 4 - row.rightWidth(), ty, row.rightColor(), false);
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
        bodyRect = rect(BODY_SCRATCH, x, bodyY, width, bodyH);
    }

    private static int[] rect(int[] out, int x, int y, int w, int h) {
        out[0] = x;
        out[1] = y;
        out[2] = w;
        out[3] = h;
        return out;
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
    // Content (built on a key change only)
    // ------------------------------------------------------------------

    private static Model build(Key key) {
        Font font = Minecraft.getInstance().font;
        List<Row> rows = buildRows(font, cfg(), FishingTracker.getInstance(), key.expanded());
        int contentW = MIN_WIDTH;
        int height = PAD * 2 - LINE_GAP;
        int lineH = font.lineHeight + LINE_GAP;
        for (Row row : rows) {
            contentW = Math.max(contentW, row.leftWidth() + (row.right() == null ? 0 : 8 + row.rightWidth()));
            height += lineH + (row.header() ? SECTION_GAP : 0);
        }
        return new Model(List.copyOf(rows), contentW, height);
    }

    /**
     * Every visible row, section by section in the player's order ({@link FishingCardLayout}).
     * Sections the player switched off contribute nothing; each section's rows - the catch list's
     * "+N more" included - stay together wherever the section is placed. When {@code expanded} the
     * catch list is uncapped (all items, cheapest included) – used by the scrollable in-inventory
     * panel; the free-floating HUD passes {@code false} and keeps the cap.
     */
    private static List<Row> buildRows(Font font, SBSConfig.FishingSettings cfg, FishingTracker tracker,
                                       boolean expanded) {
        List<Row> rows = new ArrayList<>();
        for (FishingCardSection section : FishingCardLayout.ordered(cfg)) {
            if (!FishingCardLayout.enabled(cfg, section)) {
                continue;
            }
            switch (section) {
                case CATCHES -> catchRows(rows, font, cfg, tracker, expanded);
                case RARE_DROPS -> rareDropRows(rows, font, cfg, tracker);
                case SHARDS -> shardRows(rows, font, cfg, tracker);
                case REEL_TIMER -> reelRows(rows, font, cfg, tracker);
                case BAITS_USED -> baitRows(rows, font, tracker);
                case PROFIT -> profitRows(rows, font, cfg, tracker);
            }
        }
        return rows;
    }

    /** Sea creatures live in their own left-hand list (SeaCreatureListHud); this is the loot. */
    private static void catchRows(List<Row> rows, Font font, SBSConfig.FishingSettings cfg,
                                  FishingTracker tracker, boolean expanded) {
        int valueColor = cfg.showValues ? VALUE_COLOR : SBSTheme.TEXT;
        rows.add(header(font, "Catches"));
        // With the Shards section on, shards are listed there - here too would double them up.
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : tracker.loot().entrySet()) {
            if (!(cfg.shardTracker && FishingData.isShard(entry.getKey()))) {
                sorted.add(entry);
            }
        }
        if (sorted.isEmpty()) {
            rows.add(muted(font, "none yet"));
            return;
        }
        // Most valuable first (falls back to count when a value is unknown); the cap keeps the free
        // HUD from growing past the screen, and is lifted when expanded.
        sorted.sort((a, c) -> {
            int byValue = Double.compare(tracker.valueOf(c.getKey()), tracker.valueOf(a.getKey()));
            return byValue != 0 ? byValue : Integer.compare(c.getValue(), a.getValue());
        });
        int shown = expanded ? sorted.size() : Math.min(sorted.size(), MAX_LOOT_ROWS);
        for (int i = 0; i < shown; i++) {
            Map.Entry<String, Integer> entry = sorted.get(i);
            rows.add(Row.make(font, itemLabel(tracker, entry.getKey()),
                    valueSuffix(cfg, entry.getValue(), tracker.valueOf(entry.getKey())),
                    SBSTheme.TEXT, valueColor, false));
        }
        // The last row of the section, so it moves with Catches wherever the section is placed.
        if (sorted.size() > shown) {
            rows.add(muted(font, "+" + (sorted.size() - shown) + " more (open inventory to scroll)"));
        }
    }

    private static void rareDropRows(List<Row> rows, Font font, SBSConfig.FishingSettings cfg,
                                     FishingTracker tracker) {
        int valueColor = cfg.showValues ? VALUE_COLOR : SBSTheme.TEXT;
        rows.add(header(font, "Rare Drops"));
        if (tracker.rareDrops().isEmpty()) {
            rows.add(muted(font, "none yet"));
            return;
        }
        for (Map.Entry<String, Integer> entry : tracker.rareDrops().entrySet()) {
            rows.add(Row.make(font, itemLabel(tracker, entry.getKey()),
                    valueSuffix(cfg, entry.getValue(), tracker.rareValueOf(entry.getKey())),
                    SBSTheme.TEXT, valueColor, false));
        }
    }

    private static void shardRows(List<Row> rows, Font font, SBSConfig.FishingSettings cfg,
                                  FishingTracker tracker) {
        int valueColor = cfg.showValues ? VALUE_COLOR : SBSTheme.TEXT;
        rows.add(header(font, "Shards"));
        Map<String, Integer> shards = tracker.shards();
        if (shards.isEmpty()) {
            rows.add(muted(font, "none yet"));
            return;
        }
        for (Map.Entry<String, Integer> entry : shards.entrySet()) {
            rows.add(Row.make(font, itemLabel(tracker, entry.getKey()),
                    valueSuffix(cfg, entry.getValue(), tracker.valueOf(entry.getKey())),
                    SBSTheme.TEXT, valueColor, false));
        }
    }

    private static void reelRows(List<Row> rows, Font font, SBSConfig.FishingSettings cfg,
                                 FishingTracker tracker) {
        ReelTimer reel = tracker.reelTimer();
        int rolling = cfg.reelRollingCasts;
        rows.add(header(font, "Reel Timer"));
        long wait = reel.currentWait(System.currentTimeMillis());
        rows.add(Row.make(font, "Current", wait < 0 ? "-" : seconds(wait), SBSTheme.TEXT, SBSTheme.TEXT, false));
        long mean = reel.meanMs();
        rows.add(Row.make(font, "Average", mean < 0 ? "-" : seconds(mean) + " (" + reel.samples() + ")",
                SBSTheme.TEXT, SBSTheme.TEXT, false));
        long recent = reel.rollingMeanMs(rolling);
        rows.add(Row.make(font, "Last " + rolling, recent < 0 ? "-" : seconds(recent),
                SBSTheme.TEXT, SBSTheme.TEXT, false));
    }

    /**
     * Consumed baits are the cost side; their Bazaar value is subtracted from the session. How many
     * baits are LEFT lives in its own movable icon (BaitHud), not here. No baits, no section.
     */
    private static void baitRows(List<Row> rows, Font font, FishingTracker tracker) {
        if (tracker.baitsUsed().isEmpty()) {
            return;
        }
        rows.add(header(font, "Baits Used"));
        for (Map.Entry<String, Integer> entry : tracker.baitsUsed().entrySet()) {
            // The same price the session total subtracts (instabuy), not the loot side's instasell.
            double cost = tracker.baitPrice(entry.getKey()) * entry.getValue();
            rows.add(Row.make(font, displayName(entry.getKey()) + " ×" + entry.getValue(),
                    "-" + coins(cost), SBSTheme.TEXT, COST_COLOR, false));
        }
    }

    /** The profit lines, each behind its own toggle; the section is off when all four are. */
    private static void profitRows(List<Row> rows, Font font, SBSConfig.FishingSettings cfg,
                                   FishingTracker tracker) {
        rows.add(header(font, "Profit"));
        if (cfg.profitTimesFished) {
            rows.add(Row.make(font, "Times Fished",
                    sbs.modid.client.core.util.NumberDisplay.format(tracker.timesFished()),
                    SBSTheme.TEXT, SBSTheme.TEXT, false));
        }
        if (cfg.profitSession) {
            rows.add(Row.make(font, "Session", coins(tracker.totalProfit()), SBSTheme.TEXT, VALUE_COLOR, false));
        }
        if (cfg.profitPerHour) {
            rows.add(Row.make(font, "Per Hour", coins(tracker.profitPerHour()), SBSTheme.TEXT, VALUE_COLOR, false));
        }
        if (cfg.profitTime) {
            rows.add(Row.make(font, "Time", duration(tracker.sessionMillis()), SBSTheme.TEXT, SBSTheme.TEXT, false));
        }
    }

    /** The row label for a booked item, marked "LS" when some of it came from a loot share. */
    private static String itemLabel(FishingTracker tracker, String itemId) {
        String name = displayName(itemId);
        return tracker.lootShareCount(itemId) > 0 ? name + " §7LS" : name;
    }

    private static Row header(Font font, String text) {
        return Row.make(font, text, "", SBSTheme.ACCENT, 0, true);
    }

    private static Row muted(Font font, String text) {
        return Row.make(font, text, "", SBSTheme.TEXT_MUTED, 0, false);
    }

    /** "12" or "12 · 1.2M" depending on Show Values (a zero value stays a plain count). */
    private static String valueSuffix(SBSConfig.FishingSettings cfg, int count, double value) {
        if (!cfg.showValues || value <= 0) {
            return String.valueOf(count);
        }
        return count + " · " + coins(value);
    }

    /** 12345 ms -> "12.3s". */
    private static String seconds(long millis) {
        return String.format(Locale.ROOT, "%.1fs", millis / 1000.0);
    }

    // ------------------------------------------------------------------
    // Drawing (per frame: prepared data only)
    // ------------------------------------------------------------------

    private static void draw(GuiGraphicsExtractor g, SBSConfig.FishingSettings cfg, Model model) {
        Font font = Minecraft.getInstance().font;
        int lineH = font.lineHeight + LINE_GAP;
        int width = model.contentWidth() + PAD * 2;
        int height = model.height();

        HudElement.Bounds b = HudElement.FISHING_HUD.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());

        int alpha = FishingHudPanel.alpha(cfg.hudOpacity);

        HudLayout.measure(HudElement.FISHING_HUD, x, y, width, height);
        HudLayout.begin(g, HudElement.FISHING_HUD);
        FishingHudPanel.panel(g, x, y, width, height, alpha);

        int ty = y + PAD;
        List<Row> rows = model.rows();
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (row.header() && i > 0) {
                ty += SECTION_GAP;
            }
            g.text(font, row.left(), x + PAD, ty, row.leftColor(), false);
            if (row.right() != null) {
                g.text(font, row.right(), x + width - PAD - row.rightWidth(), ty, row.rightColor(), false);
            }
            ty += lineH;
        }
        HudLayout.end(g);
    }

    // ------------------------------------------------------------------
    // Formatting
    // ------------------------------------------------------------------

    /**
     * The row label for an item id: the item's real name from the catalogue ("Thunder Fragment" for
     * THUNDER_SHARDS, "Silver Magmafish" for MAGMA_FISH_SILVER), with attribute shards and coins
     * spelled out. Prettifying the id read "Thunder Shards" for an item that is not a shard at all.
     */
    private static String displayName(String itemId) {
        if (itemId.startsWith("SHARD_")) {
            return prettify(itemId.substring("SHARD_".length())) + " Shard";
        }
        if ("SKYBLOCK_COIN".equals(itemId)) {
            return "Coins";
        }
        SkyBlockItemCatalog.Entry entry = SkyBlockItemCatalog.getInstance().byId(itemId);
        if (entry != null && entry.name != null && !entry.name.isBlank()) {
            return entry.name;
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
