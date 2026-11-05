/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.floorseven.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.phys.Vec3;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.render.FrameCamera;
import sbs.modid.client.core.render.WorldRender;
import sbs.modid.client.dungeons.floorseven.logic.RelicTracker;

/**
 * The optional line from the crosshair to where the carried M7 relic goes. The target and colour
 * are computed by {@link RelicTracker} when the run state changes; this only reads two fields per
 * frame and allocates nothing. The relic's colour word is on the target marker itself, so the line
 * never has to be told apart by colour alone.
 */
public final class RelicGuideLine {

    private RelicGuideLine() {
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        if (!ConfigManager.getInstance().get().dungeons.m7RelicGuideLine) {
            return;
        }
        Vec3 target = RelicTracker.getInstance().guideTarget();
        if (target == null || Minecraft.getInstance().player == null) {
            return;
        }
        WorldRender.tracer(g, FrameCamera.viewProjection(), FrameCamera.position(), target,
                RelicTracker.getInstance().guideColour(), 2);
    }
}
