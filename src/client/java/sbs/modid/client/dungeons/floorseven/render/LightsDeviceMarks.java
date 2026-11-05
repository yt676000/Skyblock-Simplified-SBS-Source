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
import org.joml.Matrix4fc;
import sbs.modid.client.core.render.FrameCamera;
import sbs.modid.client.core.render.WorldRender;
import sbs.modid.client.dungeons.floorseven.logic.LightsDeviceState;
import sbs.modid.client.dungeons.floorseven.logic.LightsDeviceTracker;
import sbs.modid.client.dungeons.floorseven.model.LightsCell;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.List;

/**
 * Draws the Lights answer on the device: a filled, glowing box on every lever still to flip, and one
 * label above the device carrying the count (or "recording" while the wiring is not known yet).
 *
 * <p><b>Mark language.</b> Filled glow means "flip this". The order does not matter - toggles commute
 * - so there is no "next" ring and no numbering; the count lives in the label instead of a corner
 * badge per lever. These are local helpers: the shared mark set being built in parallel should
 * replace them once it lands (noted in the spec).
 *
 * <p><b>Occlusion.</b> The HUD pipeline has no depth buffer, so the tracker decides on its scan
 * cadence which levers the camera can actually see (one outline ray each) and this class skips the
 * rest. A lever behind a wall is not drawn through it.
 *
 * <p><b>Colour.</b> The theme accent, plus shape: filled-and-outlined for "flip", text for the state.
 * Nothing is told apart by hue alone.
 *
 * <p>Render path only: reads the prepared state, allocates nothing.
 */
public final class LightsDeviceMarks {

    /** Buttons and levers are small - the box is inset to hug one rather than a whole block. */
    private static final double INSET = 0.2;

    private LightsDeviceMarks() {
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        LightsDeviceTracker tracker = LightsDeviceTracker.getInstance();
        if (!tracker.armed()) {
            return;
        }
        LightsDeviceState state = tracker.state();
        if (state.kind() == LightsDeviceState.Kind.NONE) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        Vec3 camPos = minecraft.gameRenderer.mainCamera().position();
        Matrix4fc viewProjection = FrameCamera.viewProjection();
        int accent = SBSTheme.ACCENT;
        int fill = (accent & 0x00FFFFFF) | 0x66000000;
        int edge = accent | 0xFF000000;

        List<LightsCell> toFlip = state.toFlip();
        boolean[] visible = tracker.visible();
        for (int i = 0; i < toFlip.size() && i < visible.length; i++) {
            if (!visible[i]) {
                continue;
            }
            LightsCell lever = toFlip.get(i);
            double x0 = lever.x() + INSET;
            double y0 = lever.y() + INSET;
            double z0 = lever.z() + INSET;
            double x1 = lever.x() + 1 - INSET;
            double y1 = lever.y() + 1 - INSET;
            double z1 = lever.z() + 1 - INSET;
            WorldRender.fillBox(g, viewProjection, camPos, x0, y0, z0, x1, y1, z1, fill);
            WorldRender.boxEdges(g, viewProjection, camPos, x0, y0, z0, x1, y1, z1, edge, 2);
        }

        int[] screen = SCREEN;
        if (WorldRender.project(viewProjection, camPos.x, camPos.y, camPos.z,
                tracker.labelX(), tracker.labelY(), tracker.labelZ(), g.guiWidth(), g.guiHeight(), screen)) {
            int color = state.kind() == LightsDeviceState.Kind.SOLVED || state.kind() == LightsDeviceState.Kind.PARTIAL
                    ? SBSTheme.TEXT : SBSTheme.TEXT_MUTED;
            g.centeredText(minecraft.font, tracker.label(), screen[0], screen[1], color);
        }
    }

    /** Render thread only: scratch for world-to-screen projection. */
    private static final int[] SCREEN = new int[2];
}
