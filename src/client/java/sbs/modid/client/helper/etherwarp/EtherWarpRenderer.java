/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.etherwarp;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.render.WorldRender;

/**
 * Feature 1 of the Ether Warp module: draws a box around the block the etherwarp would land on,
 * updated every frame while sneaking with an etherwarp-capable AOTE/AOTV.
 *
 * <p>Colour, transparency and line thickness are all configurable. When the landing spot is blocked
 * (no headroom) the box turns red – an impossible warp is then obvious before the button is pressed
 * rather than after. The box hugs the target block's real shape, so a slab or a stair is outlined at
 * its true height instead of as a full cube.
 *
 * <p>Purely visual: it draws nothing at all unless the highlight is enabled AND a capable weapon is
 * held while sneaking, so no other item and no normal gameplay is touched.
 */
public final class EtherWarpRenderer {

    /** Box colour for a target the player could not actually stand on. */
    private static final int INVALID_RGB = 0xFF2020;

    private EtherWarpRenderer() {
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g, float partialTick) {
        SBSConfig.EtherWarpSettings cfg = ConfigManager.getInstance().get().etherWarp;
        if (!cfg.targetHighlight || !EtherWarp.armed()) {
            return;
        }
        EtherWarp.Target target = EtherWarp.resolve(partialTick);
        if (target == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Level level = minecraft.level;
        if (level == null) {
            return;
        }
        Camera camera = minecraft.gameRenderer.mainCamera();
        Matrix4f viewProjection = camera.getViewRotationProjectionMatrix(new Matrix4f());

        int rgb = !target.valid() && cfg.markInvalid ? INVALID_RGB : cfg.highlightColor.rgb();
        int alpha = clamp(cfg.highlightOpacity, 10, 100) * 255 / 100;
        int color = (alpha << 24) | (rgb & 0xFFFFFF);

        AABB box = shapeOf(level, target.pos());
        WorldRender.boxEdges(g, viewProjection, camera.position(),
                box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ,
                color, clamp(cfg.lineWidth, 1, 5));
    }

    /** The target block's real bounds in world space, falling back to the full cube. */
    private static AABB shapeOf(Level level, BlockPos pos) {
        VoxelShape shape = level.getBlockState(pos).getCollisionShape(level, pos);
        if (shape.isEmpty()) {
            return new AABB(pos.getX(), pos.getY(), pos.getZ(),
                    pos.getX() + 1.0, pos.getY() + 1.0, pos.getZ() + 1.0);
        }
        return shape.bounds().move(pos.getX(), pos.getY(), pos.getZ());
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
