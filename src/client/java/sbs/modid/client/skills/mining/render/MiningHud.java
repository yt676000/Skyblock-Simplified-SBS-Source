/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig.MiningHelpersSettings;
import sbs.modid.client.skills.mining.logic.MiningTracker;
import sbs.modid.client.skills.mining.logic.ToolDurability;
import sbs.modid.client.skills.mining.model.Commission;
import sbs.modid.client.skills.mining.treasurechest.logic.ChestSession;
import sbs.modid.client.skills.mining.treasurechest.logic.TreasureChestTracker;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * The three Mining Helper cards: commissions, Heart of the Mountain / powder, and the held tool's
 * remaining uses.
 *
 * <p>Each is its own movable HUD element and each hides itself when it has nothing to say, so a
 * player who only wants the tool bar is not made to carry the other two around the screen.
 */
public final class MiningHud {

    private static final int PAD = 6;
    private static final int LINE_GAP = 3;
    private static final int BAR_HEIGHT = 4;
    private static final int DONE_COLOR = 0xFF57D977;
    private static final int LOW_COLOR = 0xFFFF4040;
    private static final int WARN_COLOR = 0xFFFFC12E;

    private MiningHud() {
    }

    private static MiningHelpersSettings cfg() {
        return ConfigManager.getInstance().get().miningHelpers;
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        MiningHelpersSettings settings = cfg();
        if (!settings.enabled || Minecraft.getInstance().player == null) {
            return;
        }
        renderCommissions(g, settings);
        renderHotm(g, settings);
        renderReminder(g, settings);
        renderPowder(g, settings);
        renderTool(g, settings);
    }

    // ------------------------------------------------------------------ commissions

    private static void renderCommissions(GuiGraphicsExtractor g, MiningHelpersSettings settings) {
        MiningTracker tracker = MiningTracker.getInstance();
        if (!settings.commissions || !tracker.commissionsFresh()
                || HudLayout.isHidden(HudElement.MINING_COMMISSIONS)) {
            return;
        }
        List<Commission> rows = new ArrayList<>(tracker.commissions().size());
        for (Commission commission : tracker.commissions()) {
            if (!settings.hideCompletedCommissions || !commission.done()) {
                rows.add(commission);
            }
        }
        if (rows.isEmpty()) {
            return;
        }
        HudElement.Bounds bounds =
                HudElement.MINING_COMMISSIONS.defaultBounds(g.guiWidth(), g.guiHeight());
        HudLayout.begin(g, HudElement.MINING_COMMISSIONS);
        drawCommissions(g, rows, (int) bounds.x(), (int) bounds.y());
        HudLayout.end(g);
    }

    private static void drawCommissions(GuiGraphicsExtractor g, List<Commission> rows, int x, int y) {
        Font font = Minecraft.getInstance().font;
        int rowH = font.lineHeight + BAR_HEIGHT + LINE_GAP + 2;
        int contentW = font.width("Commissions");
        for (Commission commission : rows) {
            contentW = Math.max(contentW,
                    font.width(rowName(commission)) + 10 + font.width(commission.progressLabel()));
        }
        int width = Math.max(130, PAD * 2 + contentW);
        int height = PAD * 2 + font.lineHeight + LINE_GAP + rows.size() * rowH - LINE_GAP;
        HudLayout.measure(HudElement.MINING_COMMISSIONS, x, y, width, height);
        panel(g, x, y, width, height);

        int ix = x + PAD;
        int right = x + width - PAD;
        int iy = y + PAD;
        g.text(font, Component.literal("Commissions"), ix, iy, SBSTheme.ACCENT_BRIGHT);
        iy += font.lineHeight + LINE_GAP;

        for (Commission commission : rows) {
            String label = commission.progressLabel();
            int color = commission.done() ? DONE_COLOR : SBSTheme.TEXT;
            g.text(font, Component.literal(rowName(commission)), ix, iy, SBSTheme.TEXT);
            g.text(font, Component.literal(label), right - font.width(label), iy, color);
            bar(g, ix, iy + font.lineHeight + 1, right - ix, commission.fraction(),
                    commission.done() ? DONE_COLOR : SBSTheme.ACCENT);
            iy += rowH;
        }
    }

    /**
     * The commission's name as the card writes it, carrying the two things the route has to say about
     * it: {@code »} for the one the route is pinned to, and a trailing {@code ?} for one whose place
     * could not be worked out. The question mark is the whole "say so rather than point somewhere
     * plausible" rule as it reaches the screen - without it, an unresolved commission and a resolved
     * one look identical and the missing marker reads as a bug.
     */
    private static String rowName(Commission commission) {
        if (!sbs.modid.client.core.pathfinding.PathRouting.commissionRouting()) {
            return commission.name();
        }
        var route = sbs.modid.client.skills.mining.logic.CommissionRoute.getInstance();
        String prefix = route.isPinned(commission.name()) ? "§b» §r" : "";
        String suffix = !commission.done() && route.isUnresolved(commission.name()) ? " §8?" : "";
        return prefix + commission.name() + suffix;
    }

    // ------------------------------------------------------------------ heart of the mountain

    private static void renderHotm(GuiGraphicsExtractor g, MiningHelpersSettings settings) {
        MiningTracker tracker = MiningTracker.getInstance();
        if (!settings.hotm || !tracker.hotmFresh() || HudLayout.isHidden(HudElement.MINING_HOTM)) {
            return;
        }
        HudElement.Bounds bounds = HudElement.MINING_HOTM.defaultBounds(g.guiWidth(), g.guiHeight());
        HudLayout.begin(g, HudElement.MINING_HOTM);
        drawHotm(g, tracker, (int) bounds.x(), (int) bounds.y());
        HudLayout.end(g);
    }

    private static void drawHotm(GuiGraphicsExtractor g, MiningTracker tracker, int x, int y) {
        Font font = Minecraft.getInstance().font;
        String title = "Heart of the Mountain " + tracker.hotmTier();

        int width = Math.max(140, PAD * 2 + font.width(title));
        int height = PAD * 2 + font.lineHeight;
        HudLayout.measure(HudElement.MINING_HOTM, x, y, width, height);
        panel(g, x, y, width, height);
        g.text(font, Component.literal(title), x + PAD, y + PAD, SBSTheme.ACCENT_BRIGHT);
    }

    // ------------------------------------------------------------------ hotm upgrade reminder

    /**
     * The HotM Upgrade Reminder's line. Its text is built once a second by {@code HotmReminder} and
     * is empty off the mining islands, so this only draws.
     */
    private static void renderReminder(GuiGraphicsExtractor g, MiningHelpersSettings settings) {
        String line = sbs.modid.client.skills.mining.logic.HotmReminder.getInstance().hudLine();
        if (!settings.hotmReminderHud || line.isEmpty() || HudLayout.isHidden(HudElement.HOTM_REMINDER)) {
            return;
        }
        HudElement.Bounds bounds = HudElement.HOTM_REMINDER.defaultBounds(g.guiWidth(), g.guiHeight());
        HudLayout.begin(g, HudElement.HOTM_REMINDER);
        Font font = Minecraft.getInstance().font;
        int x = (int) bounds.x();
        int y = (int) bounds.y();
        int width = PAD * 2 + font.width(line);
        int height = PAD * 2 + font.lineHeight;
        HudLayout.measure(HudElement.HOTM_REMINDER, x, y, width, height);
        panel(g, x, y, width, height);
        g.text(font, Component.literal(line), x + PAD, y + PAD, SBSTheme.TEXT);
        HudLayout.end(g);
    }

    // ------------------------------------------------------------------ powders

    /**
     * The powder card. Its own element and toggle rather than rows inside the Heart of the Mountain
     * card: the two answer different questions, and sharing one card meant they could not be placed
     * or switched off independently.
     *
     * <p>Two halves, each drawn when it has something: the tab list's totals, and the treasure
     * chests opened this session with the powder they paid. The chest half does not wait for the tab
     * half - that parse has never been seen working live, and the chest count must not go dark with
     * it.
     */
    private static void renderPowder(GuiGraphicsExtractor g, MiningHelpersSettings settings) {
        MiningTracker tracker = MiningTracker.getInstance();
        boolean tab = settings.powder && tracker.powderFresh();
        boolean chests = settings.treasureChestCounter
                && !TreasureChestTracker.getInstance().session().isEmpty();
        if ((!tab && !chests) || HudLayout.isHidden(HudElement.MINING_POWDER)) {
            return;
        }
        HudElement.Bounds bounds = HudElement.MINING_POWDER.defaultBounds(g.guiWidth(), g.guiHeight());
        HudLayout.begin(g, HudElement.MINING_POWDER);
        drawPowder(g, tracker, settings, tab, chests, (int) bounds.x(), (int) bounds.y());
        HudLayout.end(g);
    }

    private static void drawPowder(GuiGraphicsExtractor g, MiningTracker tracker,
                                   MiningHelpersSettings settings, boolean tab, boolean chests,
                                   int x, int y) {
        Font font = Minecraft.getInstance().font;
        String title = "Powders";

        List<String[]> rows = new ArrayList<>();   // [label, value]
        java.util.Map<String, Long> totals = tab ? tracker.powder() : java.util.Map.of();
        for (java.util.Map.Entry<String, Long> entry : totals.entrySet()) {
            String name = entry.getKey();
            long gained = tracker.powderGained(name);
            StringBuilder value = new StringBuilder(compact(entry.getValue()));
            if (gained > 0) {
                value.append("  +").append(compact(gained));
                long rate = settings.powderRate ? tracker.powderPerHour(name) : 0L;
                if (rate > 0) {
                    value.append("  ").append(compact(rate)).append("/h");
                }
            }
            rows.add(new String[] {name, value.toString()});
        }
        if (chests) {
            addChestRows(rows);
        }

        int lineH = font.lineHeight + LINE_GAP;
        int contentW = font.width(title);
        for (String[] row : rows) {
            contentW = Math.max(contentW, font.width(row[0]) + 12 + font.width(row[1]));
        }
        int width = Math.max(140, PAD * 2 + contentW);
        int height = PAD * 2 + lineH * (1 + rows.size()) - LINE_GAP;
        HudLayout.measure(HudElement.MINING_POWDER, x, y, width, height);
        panel(g, x, y, width, height);

        int ix = x + PAD;
        int right = x + width - PAD;
        int iy = y + PAD;
        g.text(font, Component.literal(title), ix, iy, SBSTheme.ACCENT_BRIGHT);
        iy += lineH;
        for (String[] row : rows) {
            g.text(font, Component.literal(row[0]), ix, iy, SBSTheme.TEXT_MUTED);
            g.text(font, Component.literal(row[1]), right - font.width(row[1]), iy, SBSTheme.TEXT);
            iy += lineH;
        }
    }

    /**
     * The treasure-chest half: "Treasure Chests  12  30/h", then one row per powder type the reward
     * lines named. Indented, so it reads as a breakdown and not as a second set of totals.
     */
    private static void addChestRows(List<String[]> rows) {
        ChestSession session = TreasureChestTracker.getInstance().session();
        long now = System.currentTimeMillis();
        StringBuilder count = new StringBuilder(compact(session.chests()));
        long chestRate = session.perHour(session.chests(), now);
        if (chestRate > 0) {
            count.append("  ").append(compact(chestRate)).append("/h");
        }
        rows.add(new String[] {"Treasure Chests", count.toString()});
        for (java.util.Map.Entry<String, Long> entry : session.powder().entrySet()) {
            StringBuilder value = new StringBuilder("+").append(compact(entry.getValue()));
            long rate = session.perHour(entry.getValue(), now);
            if (rate > 0) {
                value.append("  ").append(compact(rate)).append("/h");
            }
            rows.add(new String[] {"  " + entry.getKey(), value.toString()});
        }
    }

    // ------------------------------------------------------------------ tool durability

    private static void renderTool(GuiGraphicsExtractor g, MiningHelpersSettings settings) {
        ToolDurability.Uses uses = ToolDurability.getInstance().current();
        if (!settings.toolDurability || uses == null
                || HudLayout.isHidden(HudElement.MINING_TOOL)) {
            return;
        }
        HudElement.Bounds bounds = HudElement.MINING_TOOL.defaultBounds(g.guiWidth(), g.guiHeight());
        HudLayout.begin(g, HudElement.MINING_TOOL);
        drawTool(g, uses, (int) bounds.x(), (int) bounds.y());
        HudLayout.end(g);
    }

    private static void drawTool(GuiGraphicsExtractor g, ToolDurability.Uses uses, int x, int y) {
        Font font = Minecraft.getInstance().font;
        String value = compact(uses.left()) + " / " + compact(uses.max());
        int contentW = Math.max(font.width(uses.label()), font.width(value) + 12 + font.width("100%"));
        int width = Math.max(120, PAD * 2 + contentW);
        int height = PAD * 2 + font.lineHeight * 2 + LINE_GAP + BAR_HEIGHT + 2;
        HudLayout.measure(HudElement.MINING_TOOL, x, y, width, height);
        panel(g, x, y, width, height);

        // The bar's colour IS the warning: at a glance you are looking for "is it still green".
        int color = uses.percent() <= 10 ? LOW_COLOR : uses.percent() <= 25 ? WARN_COLOR : DONE_COLOR;
        int ix = x + PAD;
        int right = x + width - PAD;
        int iy = y + PAD;
        g.text(font, Component.literal(uses.label()), ix, iy, SBSTheme.ACCENT_BRIGHT);
        iy += font.lineHeight + LINE_GAP;
        String percent = uses.percent() + "%";
        g.text(font, Component.literal(value), ix, iy, SBSTheme.TEXT);
        g.text(font, Component.literal(percent), right - font.width(percent), iy, color);
        bar(g, ix, iy + font.lineHeight + 1, right - ix, uses.fraction(), color);
    }

    // ------------------------------------------------------------------ shared drawing

    private static void panel(GuiGraphicsExtractor g, int x, int y, int width, int height) {
        HudCard.draw(g, x, y, width, height);
    }

    private static void bar(GuiGraphicsExtractor g, int x, int y, int width, float fraction, int color) {
        SciFiRender.roundedRect(g, x, y, width, BAR_HEIGHT, 1, SBSTheme.SEARCH_FILL);
        int filled = Math.round(width * Math.max(0f, Math.min(1f, fraction)));
        if (filled > 0) {
            SciFiRender.roundedRect(g, x, y, filled, BAR_HEIGHT, 1, color);
        }
    }

    /** 1_234_567 -> "1.2M", 12_400 -> "12.4K", 950 -> "950" (unless "Shorten Numbers" is off). */
    private static String compact(long value) {
        return sbs.modid.client.core.util.NumberDisplay.format(value);
    }
}
