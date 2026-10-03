/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.buffs.consumables.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.helper.buffs.consumables.logic.ConsumableStore;
import sbs.modid.client.helper.buffs.consumables.logic.DurationFormat;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * The Consumable Timers card: a header and one row per active timer, name left, time right, soonest
 * end first. {@code ~} before a time marks an estimate, {@code ‖} a paused timer - both as a
 * character, not only as a colour. Draws only while something is tracked.
 *
 * <p>The rows are rebuilt four times a second and only drawn per frame (core/AGENTS.md: a card keeps
 * its cost down by computing its text on a throttle).
 */
public final class ConsumableHud {

    private static final int PAD = 5;
    private static final long REBUILD_MS = 250L;

    private static List<String[]> rows = List.of();
    private static long builtAt;

    private ConsumableHud() {
    }

    public static void render(GuiGraphicsExtractor g) {
        var cfg = ConfigManager.getInstance().get().buffs;
        if (!cfg.enabled || !cfg.consumablesEnabled || !cfg.consumablesCard
                || Minecraft.getInstance().player == null || HudLayout.isHidden(HudElement.CONSUMABLE_TIMERS)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - builtAt >= REBUILD_MS) {
            builtAt = now;
            rows = build(cfg.consumablesHideAboveHours);
        }
        if (rows.isEmpty()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        int lineH = font.lineHeight + 2;
        String header = "Consumables";
        int labelW = 0;
        int timeW = 0;
        for (String[] row : rows) {
            labelW = Math.max(labelW, font.width(row[0]));
            timeW = Math.max(timeW, font.width(row[1]));
        }
        int width = Math.max(Math.max(110, font.width(header) + PAD * 2), labelW + 8 + timeW + PAD * 2);
        int height = PAD * 2 + lineH * (1 + rows.size()) - 2;

        HudElement.Bounds b = HudElement.CONSUMABLE_TIMERS.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());
        HudLayout.measure(HudElement.CONSUMABLE_TIMERS, x, y, width, height);

        HudLayout.begin(g, HudElement.CONSUMABLE_TIMERS);
        HudCard.draw(g, x, y, width, height);
        g.text(font, Component.literal(header), x + PAD, y + PAD, SBSTheme.ACCENT_BRIGHT);
        int iy = y + PAD + lineH;
        int right = x + width - PAD;
        for (String[] row : rows) {
            g.text(font, Component.literal(row[0]), x + PAD, iy, SBSTheme.TEXT);
            g.text(font, Component.literal(row[1]), right - font.width(row[1]), iy, SBSTheme.TEXT_MUTED);
            iy += lineH;
        }
        HudLayout.end(g);
    }

    private static List<String[]> build(int hideAboveHours) {
        long hideAbove = hideAboveHours > 0 ? hideAboveHours * 3_600_000L : Long.MAX_VALUE;
        List<String[]> out = new ArrayList<>();
        for (ConsumableStore.Row row : ConsumableStore.getInstance().rows()) {
            if (row.remainingMs() > hideAbove) {
                continue;
            }
            String time = (row.paused() ? "‖ " : "") + (row.estimated() && row.remainingMs() >= 0 ? "~" : "")
                    + DurationFormat.remaining(row.remainingMs());
            out.add(new String[] {row.label(), time});
        }
        return out;
    }
}
