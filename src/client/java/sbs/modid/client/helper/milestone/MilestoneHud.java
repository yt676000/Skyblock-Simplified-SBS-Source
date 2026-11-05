/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.milestone;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

/**
 * The milestone card: the value on one line, its progress under it.
 *
 * <p>Drawn only while the widget is actually publishing a milestone - a permanent empty card would
 * cost screen space to say nothing, and outside a run there is nothing to say.
 */
public final class MilestoneHud {

    private static final int PAD = 5;
    private static final String HEADER = "Milestone";
    private static final Component HEADER_TEXT = Component.literal(HEADER);

    /** The drawn texts, re-wrapped only when the tracker publishes a new string (identity check). */
    private static String builtValue;
    private static Component valueText;
    private static String builtProgress;
    private static Component progressText;

    private MilestoneHud() {
    }

    private static SBSConfig.MilestoneSettings cfg() {
        return ConfigManager.getInstance().get().milestone;
    }

    public static void render(GuiGraphicsExtractor g) {
        var cfg = cfg();
        if (!cfg.enabled || Minecraft.getInstance().player == null
                || HudLayout.isHidden(HudElement.MILESTONE)) {
            return;
        }
        MilestoneTracker tracker = MilestoneTracker.getInstance();
        String value = tracker.milestone();
        if (value == null) {
            return;
        }
        String progress = cfg.showProgress ? tracker.progress() : null;

        Font font = Minecraft.getInstance().font;
        int lineH = font.lineHeight + 2;
        int contentW = Math.max(font.width(HEADER) + 12 + font.width(value),
                progress == null ? 0 : font.width(progress));
        int width = Math.max(96, contentW + PAD * 2);
        int rows = progress == null ? 1 : 2;
        int height = PAD * 2 + lineH * rows - 2;

        HudElement.Bounds b = HudElement.MILESTONE.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());
        HudLayout.measure(HudElement.MILESTONE, x, y, width, height);

        HudLayout.begin(g, HudElement.MILESTONE);
        HudCard.draw(g, x, y, width, height);

        int ix = x + PAD;
        int right = x + width - PAD;
        int iy = y + PAD;
        if (value != builtValue) {
            builtValue = value;
            valueText = Component.literal(value);
        }
        g.text(font, HEADER_TEXT, ix, iy, SBSTheme.ACCENT_BRIGHT);
        g.text(font, valueText, right - font.width(value), iy, SBSTheme.TEXT);
        if (progress != null) {
            if (progress != builtProgress) {
                builtProgress = progress;
                progressText = Component.literal(progress);
            }
            g.text(font, progressText, ix, iy + lineH, SBSTheme.TEXT_MUTED);
        }
        HudLayout.end(g);
    }
}
