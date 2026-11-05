/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.floorseven.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.dungeons.floorseven.logic.M7TickTimers;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.render.HudCard;

/**
 * The M7 Tick Timers card ("Pad: 0.85s" in P2, "Goldor: 1.40s" in P3) and the optional large
 * countdown in the middle of the screen for the last second. Draws the model
 * {@link M7TickTimers} rebuilt this tick; computes nothing itself. Movable via the GUI editor.
 */
public final class M7TickTimersHud {

    private static final int PAD = 6;
    private static final int MIN_W = 90;
    private static final float CENTRE_SCALE = 3.0F;

    private M7TickTimersHud() {
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        if (!ConfigManager.getInstance().get().dungeons.m7TickTimers
                || Minecraft.getInstance().player == null) {
            return;
        }
        M7TickTimers timers = M7TickTimers.getInstance();
        Component line = timers.line();
        if (line == null) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        if (!HudLayout.isHidden(HudElement.M7_TICK_TIMERS)) {
            HudElement.Bounds bounds = HudElement.M7_TICK_TIMERS.defaultBounds(g.guiWidth(), g.guiHeight());
            int x = (int) bounds.x();
            int y = (int) bounds.y();
            int width = Math.max(MIN_W, PAD * 2 + timers.lineWidth());
            int height = PAD * 2 + font.lineHeight;
            HudLayout.begin(g, HudElement.M7_TICK_TIMERS);
            HudLayout.measure(HudElement.M7_TICK_TIMERS, x, y, width, height);
            HudCard.draw(g, x, y, width, height);
            g.text(font, line, x + PAD, y + PAD, timers.lineColor());
            HudLayout.end(g);
        }
        Component centre = timers.centre();
        if (centre != null) {
            float cx = g.guiWidth() / 2.0F;
            float cy = g.guiHeight() / 2.0F - 30.0F;
            g.pose().pushMatrix();
            g.pose().translate(cx, cy);
            g.pose().scale(CENTRE_SCALE);
            g.centeredText(font, centre, 0, 0, timers.centreColor());
            g.pose().popMatrix();
        }
    }
}
