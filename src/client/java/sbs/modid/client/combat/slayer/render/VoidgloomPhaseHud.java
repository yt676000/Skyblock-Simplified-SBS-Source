/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.slayer.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.combat.slayer.logic.VoidgloomFight;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;

/**
 * Your own Voidgloom's shield and laser phase as bare text under the crosshair: "Shield: 34 hits",
 * coloured from red (full) to green (nearly broken), and "Laser: 6.2s" counting the phase down.
 *
 * <p>The laser line carries a "~" while its length is still the unconfirmed estimate, and drops it
 * once a phase has been measured this session. Lines are rebuilt only when their value changes, not
 * every frame.
 */
public final class VoidgloomPhaseHud {

    private static final int LINE_GAP = 2;
    private static final int FULL_COLOR = 0xFFFF5555;
    private static final int HALF_COLOR = 0xFFFFAA00;
    private static final int LOW_COLOR = 0xFF55FF55;
    private static final int LASER_COLOR = 0xFFFF55FF;

    private static int shownHits = Integer.MIN_VALUE;
    private static Component shieldLine;
    private static int shieldWidth;
    private static long shownTenths = Long.MIN_VALUE;
    private static boolean shownEstimate;
    private static Component laserLine;
    private static int laserWidth;

    private VoidgloomPhaseHud() {
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        var cfg = ConfigManager.getInstance().get().slayer;
        VoidgloomFight fight = VoidgloomFight.getInstance();
        if (!cfg.enabled || !fight.active() || Minecraft.getInstance().player == null
                || HudLayout.isHidden(HudElement.VOIDGLOOM_PHASE)) {
            return;
        }
        boolean shield = cfg.voidgloomShieldHud && fight.shieldHits() >= 0;
        boolean laser = cfg.voidgloomLaserTimer && fight.laserActive();
        if (!shield && !laser) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        if (shield && fight.shieldHits() != shownHits) {
            shownHits = fight.shieldHits();
            shieldLine = Component.literal("Shield: " + shownHits + (shownHits == 1 ? " hit" : " hits"));
            shieldWidth = font.width(shieldLine);
        }
        if (laser) {
            long tenths = fight.laserRemainingMs(System.currentTimeMillis()) / 100;
            boolean estimate = fight.laserLengthEstimated();
            if (tenths != shownTenths || estimate != shownEstimate) {
                shownTenths = tenths;
                shownEstimate = estimate;
                laserLine = Component.literal("Laser: " + (estimate ? "~" : "")
                        + tenths / 10 + "." + tenths % 10 + "s");
                laserWidth = font.width(laserLine);
            }
        }

        HudElement.Bounds bounds = HudElement.VOIDGLOOM_PHASE.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = (int) bounds.x();
        int y = (int) bounds.y();
        int width = Math.max(shield ? shieldWidth : 0, laser ? laserWidth : 0);
        int lines = (shield ? 1 : 0) + (laser ? 1 : 0);
        int height = lines * font.lineHeight + (lines - 1) * LINE_GAP;
        HudLayout.begin(g, HudElement.VOIDGLOOM_PHASE);
        HudLayout.measure(HudElement.VOIDGLOOM_PHASE, x, y, width, height);
        int centre = x + width / 2;
        if (shield) {
            g.text(font, shieldLine, centre - shieldWidth / 2, y, shieldColor(fight));
            y += font.lineHeight + LINE_GAP;
        }
        if (laser) {
            g.text(font, laserLine, centre - laserWidth / 2, y, LASER_COLOR);
        }
        HudLayout.end(g);
    }

    /** Red while most of the shield stands, orange past a third gone, green in the last third. */
    static int shieldColor(VoidgloomFight fight) {
        int max = Math.max(1, fight.shieldMax());
        double left = fight.shieldHits() / (double) max;
        return left > 2 / 3.0 ? FULL_COLOR : left > 1 / 3.0 ? HALF_COLOR : LOW_COLOR;
    }
}
