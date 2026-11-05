/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.springboots.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.helper.springboots.logic.SpringBootsTracker;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.theme.SBSTheme;

/**
 * The Spring Boots charge meter: one segment per charge step plus a "Charge 4/6" line, drawn only
 * while a charge runs. Either pinned under the crosshair or placed through the HUD editor as
 * {@link HudElement#SPRING_BOOTS_CHARGE}.
 *
 * <p>HOT path (every frame), but it only draws: the label and its width are prepared on the tick by
 * {@link SpringBootsTracker}, and the meter is a handful of fills.
 */
public final class SpringBootsHud {

    private static final int PAD = 4;
    private static final int SEG_W = 8;
    private static final int SEG_H = 4;
    private static final int SEG_GAP = 2;
    /** Distance below the crosshair centre when pinned. */
    private static final int CROSSHAIR_OFFSET = 12;

    private static final int EMPTY = 0x60FFFFFF;
    private static final int FULL = 0xFF57D977;

    private SpringBootsHud() {
    }

    public static void render(GuiGraphicsExtractor g) {
        SBSConfig.SpringBootsSettings cfg = ConfigManager.getInstance().get().springBoots;
        SpringBootsTracker tracker = SpringBootsTracker.getInstance();
        if (!cfg.enabled || !tracker.active() || HudLayout.isHidden(HudElement.SPRING_BOOTS_CHARGE)) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        int max = tracker.max();
        int level = tracker.level();
        boolean full = tracker.full();
        int meterW = max * SEG_W + (max - 1) * SEG_GAP;
        int width = Math.max(meterW, tracker.labelWidth()) + PAD * 2;
        int height = PAD * 2 + SEG_H + 3 + font.lineHeight;

        int x;
        int y;
        boolean movable = !cfg.pinToCrosshair;
        if (movable) {
            HudElement.Bounds b = HudElement.SPRING_BOOTS_CHARGE.defaultBounds(g.guiWidth(), g.guiHeight());
            x = Math.round(b.x());
            y = Math.round(b.y());
            HudLayout.measure(HudElement.SPRING_BOOTS_CHARGE, x, y, width, height);
            HudLayout.begin(g, HudElement.SPRING_BOOTS_CHARGE);
        } else {
            x = g.guiWidth() / 2 - width / 2;
            y = g.guiHeight() / 2 + CROSSHAIR_OFFSET;
        }

        HudCard.draw(g, x, y, width, height);
        int segX = x + (width - meterW) / 2;
        int segY = y + PAD;
        int on = full ? FULL : SBSTheme.ACCENT;
        for (int i = 0; i < max; i++) {
            int sx = segX + i * (SEG_W + SEG_GAP);
            g.fill(sx, segY, sx + SEG_W, segY + SEG_H, i < level ? on : EMPTY);
        }
        g.text(font, tracker.label(), x + (width - tracker.labelWidth()) / 2, segY + SEG_H + 3,
                full ? FULL : SBSTheme.TEXT_MUTED);

        if (movable) {
            HudLayout.end(g);
        }
    }
}
