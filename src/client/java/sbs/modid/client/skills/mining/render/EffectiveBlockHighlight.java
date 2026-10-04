/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.render;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.render.OverlayColor;
import sbs.modid.client.core.render.WorldRender;
import sbs.modid.client.skills.mining.logic.EffectiveBlockScanner;
import sbs.modid.client.skills.mining.model.EffectiveBlockTable.Tier;

/**
 * Effective Ore Blocks: tints each visible face of a Mithril, Umber or Tungsten block by how much
 * the block yields per unit of mining effort, ranked within its own ore - green for the best block
 * of that ore, yellow for the middle, and the lowest tier off unless the player turns it on.
 *
 * <p>Only draws what {@link EffectiveBlockScanner} has already passed as visible: exposed faces
 * with a clear line from the camera. Through the same HUD world-render pass and
 * {@link WorldRender} helpers as {@link PickobolusHighlight}; there is no second renderer.
 *
 * <p>Display only - it colours blocks the player is already looking at, and never aims, selects
 * or cycles anything.
 */
public final class EffectiveBlockHighlight {

    /** Default tier colours (RGB), used while the config value is empty or unreadable. */
    public static final int DEFAULT_BEST = 0x33DD55;
    public static final int DEFAULT_MIDDLE = 0xE6C84A;
    public static final int DEFAULT_LOW = 0xD9534F;

    /** The tint's alpha: enough to read the tier, little enough to still see the block's texture. */
    private static final int TINT_ALPHA = 0x55;

    /** Faces drawn per frame at most, nearest first. */
    private static final int MAX_DRAWN = 600;

    /** Scan-fill strip width in GUI pixels; invisible at a translucent alpha and a third the fills. */
    private static final int STRIP = 2;

    private EffectiveBlockHighlight() {
    }

    /** Called from the HUD world-render pass once per frame. Self-gating. */
    public static void render(GuiGraphicsExtractor g) {
        SBSConfig.MiningHelpersSettings cfg = ConfigManager.getInstance().get().miningHelpers;
        EffectiveBlockScanner scanner = EffectiveBlockScanner.getInstance();
        if (!cfg.effectiveBlocks || !scanner.armed()) {
            return;
        }
        EffectiveBlockScanner.VisibleFaces faces = scanner.visible();
        if (faces.size() == 0) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }
        Camera camera = minecraft.gameRenderer.mainCamera();
        Vec3 camPos = camera.position();
        Matrix4f viewProjection = camera.getViewRotationProjectionMatrix(new Matrix4f());
        int best = colour(cfg.effectiveBestColorHex, DEFAULT_BEST);
        int middle = colour(cfg.effectiveMiddleColorHex, DEFAULT_MIDDLE);
        int low = colour(cfg.effectiveLowColorHex, DEFAULT_LOW);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        int drawn = 0;
        for (int i = 0; i < faces.size() && drawn < MAX_DRAWN; i++) {
            EffectiveBlockScanner.Candidate candidate = faces.candidate(i);
            Tier tier = scanner.tierOf(candidate.entry());
            if (tier == null || (cfg.effectiveBestOnly && tier != Tier.BEST)
                    || (tier == Tier.LOW && !cfg.effectiveShowLow)) {
                continue;
            }
            int x = BlockPos.getX(candidate.pos());
            int y = BlockPos.getY(candidate.pos());
            int z = BlockPos.getZ(candidate.pos());
            // Mined since the last scan: it is bedrock or titanium now, so the tint goes at once
            // rather than on the next tick.
            if (level.getBlockState(cursor.set(x, y, z)).getBlock() != candidate.block()) {
                continue;
            }
            int face = faces.face(i);
            // A face the camera has moved behind since the tick's test is not drawn either.
            double nx = EffectiveBlockScanner.FACE_DX[face];
            double ny = EffectiveBlockScanner.FACE_DY[face];
            double nz = EffectiveBlockScanner.FACE_DZ[face];
            if ((camPos.x - (x + 0.5 + nx * 0.5)) * nx + (camPos.y - (y + 0.5 + ny * 0.5)) * ny
                    + (camPos.z - (z + 0.5 + nz * 0.5)) * nz <= 0) {
                continue;
            }
            int colour = switch (tier) {
                case BEST -> best;
                case MIDDLE -> middle;
                case LOW -> low;
            };
            drawFace(g, viewProjection, camPos, x, y, z, face, colour, cfg.effectiveOutline);
            drawn++;
        }
    }

    /** One block face as a translucent quad, and optionally its four edges at full opacity. */
    private static void drawFace(GuiGraphicsExtractor g, Matrix4f viewProjection, Vec3 camPos,
                                 int x, int y, int z, int face, int colour, boolean outline) {
        Vec3[] q = corners(x, y, z, face);
        WorldRender.fillQuad(g, viewProjection, camPos, q[0], q[1], q[2], q[3], colour, STRIP);
        if (outline) {
            int edge = colour | 0xFF000000;
            for (int k = 0; k < 4; k++) {
                WorldRender.line3d(g, viewProjection, camPos, q[k], q[(k + 1) % 4], edge, 1);
            }
        }
    }

    /** The four corners of a block face, in winding order, indexed like {@code Direction}. */
    private static Vec3[] corners(int x, int y, int z, int face) {
        double x0 = x;
        double y0 = y;
        double z0 = z;
        double x1 = x + 1.0;
        double y1 = y + 1.0;
        double z1 = z + 1.0;
        return switch (face) {
            case 0 -> new Vec3[] {v(x0, y0, z0), v(x1, y0, z0), v(x1, y0, z1), v(x0, y0, z1)};  // down
            case 1 -> new Vec3[] {v(x0, y1, z0), v(x1, y1, z0), v(x1, y1, z1), v(x0, y1, z1)};  // up
            case 2 -> new Vec3[] {v(x0, y0, z0), v(x1, y0, z0), v(x1, y1, z0), v(x0, y1, z0)};  // north
            case 3 -> new Vec3[] {v(x0, y0, z1), v(x1, y0, z1), v(x1, y1, z1), v(x0, y1, z1)};  // south
            case 4 -> new Vec3[] {v(x0, y0, z0), v(x0, y0, z1), v(x0, y1, z1), v(x0, y1, z0)};  // west
            default -> new Vec3[] {v(x1, y0, z0), v(x1, y0, z1), v(x1, y1, z1), v(x1, y1, z0)}; // east
        };
    }

    private static Vec3 v(double x, double y, double z) {
        return new Vec3(x, y, z);
    }

    /** The configured colour at the tint alpha, or the default while it is empty or unreadable. */
    private static int colour(String hex, int fallback) {
        Integer parsed = OverlayColor.parseHex(hex);
        return ((parsed == null ? fallback : parsed) & 0xFFFFFF) | (TINT_ALPHA << 24);
    }
}
