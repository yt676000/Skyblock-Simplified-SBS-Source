/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.farming.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.util.NumberDisplay;
import sbs.modid.client.skills.SkillIslands;
import sbs.modid.client.skills.farming.logic.FarmDropTracker;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The Farm Drops card: this session's rare farming drops ("Cropie 3 · all-time 41"), their value
 * and the rate. A card of its own rather than rows on the Farming Tracker, which is driven by the
 * tab widgets and hides with them. On a farming island only, and only once a drop has been counted.
 */
public final class FarmDropHud {

    private FarmDropHud() {
    }

    /** From the HUD hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        var cfg = ConfigManager.getInstance().get().farming;
        if (!cfg.farmDrops || !cfg.farmDropsCard || HudLayout.isHidden(HudElement.FARM_DROPS)
                || Minecraft.getInstance().player == null || !SkillIslands.onFarmingIsland()) {
            return;
        }
        FarmDropTracker tracker = FarmDropTracker.getInstance();
        Map<String, Integer> session = tracker.session();
        if (session.isEmpty()) {
            return;
        }
        Map<String, Integer> totals = tracker.totals();
        List<String[]> rows = new ArrayList<>();
        for (var e : session.entrySet()) {
            rows.add(new String[]{e.getKey(),
                    e.getValue() + " §8· all-time " + totals.getOrDefault(e.getKey(), e.getValue())});
        }
        boolean[] unpriced = {false};
        long value = tracker.sessionValue(unpriced);
        rows.add(new String[]{"Value", NumberDisplay.shorten((double) value) + (unpriced[0] ? "+" : "")});
        double perHour = tracker.perHour();
        if (perHour >= 0) {
            rows.add(new String[]{"Per hour", String.format(java.util.Locale.ROOT, "%.1f", perHour)});
        }

        Font font = Minecraft.getInstance().font;
        int pad = 5;
        int lineH = font.lineHeight + 2;
        int contentW = font.width("Farm Drops");
        for (String[] row : rows) {
            contentW = Math.max(contentW, font.width(row[0]) + 12 + font.width(row[1]));
        }
        int width = Math.max(120, contentW + pad * 2);
        int height = pad * 2 + lineH * (1 + rows.size()) - 2;

        HudElement.Bounds b = HudElement.FARM_DROPS.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());
        HudLayout.measure(HudElement.FARM_DROPS, x, y, width, height);
        HudLayout.begin(g, HudElement.FARM_DROPS);
        HudCard.draw(g, x, y, width, height);
        int ix = x + pad;
        int right = x + width - pad;
        int iy = y + pad;
        g.text(font, Component.literal("Farm Drops"), ix, iy, SBSTheme.ACCENT_BRIGHT);
        iy += lineH;
        for (String[] row : rows) {
            g.text(font, Component.literal(row[0]), ix, iy, SBSTheme.TEXT_MUTED);
            g.text(font, Component.literal(row[1]), right - font.width(row[1]), iy, SBSTheme.TEXT);
            iy += lineH;
        }
        HudLayout.end(g);
    }
}
