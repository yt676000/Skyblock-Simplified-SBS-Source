/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.metaldetector.render;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.render.WorldRender;
import sbs.modid.client.skills.mining.metaldetector.logic.MetalDetectorSolver;
import sbs.modid.client.skills.mining.metaldetector.logic.MetalDetectorTracker;

/**
 * The solved treasure block, outlined in the world.
 *
 * <p>Read-only, like the rest of this feature: a box and nothing else. No walking, no digging, no
 * aiming - the player is told where to look and does the rest.
 *
 * <h2>The outline says how sure it is</h2>
 * Two things make a fix weaker, and both change what is drawn rather than being buried in a log:
 * <ul>
 *   <li><b>A derived height.</b> Readings all taken at one level pin the horizontal position exactly
 *       and the depth only by inference, so that box is drawn taller than a block and in a dimmer
 *       colour - it is honestly a column to dig down, not a cube to break.</li>
 *   <li><b>Readings that disagree.</b> A large residual means one of them belongs to a treasure that
 *       is already gone; the box grows with it, so a fix nobody should trust looks like one.</li>
 * </ul>
 * A confidently wrong spot is worse than no spot, and an outline that is always the same size is
 * exactly how a wrong spot ends up looking confident.
 */
public final class MetalDetectorRender {

    /** Solid enough to find, transparent enough not to hide the block you are about to mine. */
    private static final int SURE = 0xFF49C7B0;
    private static final int UNSURE = 0xA0C7A849;

    /** Past this disagreement between readings the fix is a region, not a point. */
    private static final double SLOPPY_RESIDUAL = 1.5;

    private MetalDetectorRender() {
    }

    /** Called from {@code HudMixin.extractHotbarAndDecorations} at TAIL; self-gating. */
    public static void render(GuiGraphicsExtractor g) {
        if (!ConfigManager.getInstance().get().metalDetector.box) {
            return;
        }
        MetalDetectorTracker tracker = MetalDetectorTracker.getInstance();
        if (!tracker.active()) {
            return;
        }
        MetalDetectorSolver.Fix fix = tracker.fix();
        if (fix == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        Camera camera = minecraft.gameRenderer.mainCamera();
        Vec3 camPos = camera.position();
        Matrix4f viewProjection = camera.getViewRotationProjectionMatrix(new Matrix4f());

        boolean sure = !fix.heightIsDerived() && fix.residual() <= SLOPPY_RESIDUAL;
        // Grows with the disagreement between readings: the box is the uncertainty, drawn.
        double pad = Math.min(3.0, Math.max(0.0, fix.residual() - 0.5) / 2.0);
        double x = Math.floor(fix.x());
        double y = Math.floor(fix.y());
        double z = Math.floor(fix.z());
        // A derived height is a column to dig down, not a block to break, so it is drawn as one.
        double down = fix.heightIsDerived() ? 2.0 : 0.0;
        double up = fix.heightIsDerived() ? 2.0 : 0.0;

        WorldRender.boxEdges(g, viewProjection, camPos,
                x - pad, y - pad - down, z - pad,
                x + 1 + pad, y + 1 + pad + up, z + 1 + pad,
                sure ? SURE : UNSURE, sure ? 2 : 1);
    }
}
