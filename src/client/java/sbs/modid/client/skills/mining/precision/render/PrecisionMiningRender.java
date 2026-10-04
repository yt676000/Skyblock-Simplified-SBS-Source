/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.precision.render;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig.MiningHelpersSettings;
import sbs.modid.client.core.render.WorldRender;
import sbs.modid.client.skills.SkillIslands;
import sbs.modid.client.skills.mining.precision.logic.PrecisionMiningTracker;
import sbs.modid.client.skills.mining.precision.logic.PrecisionTarget;

/**
 * The Precision Mining marker: a small square on the face of the block you are mining, exactly where
 * the perk's particle sits, red while your crosshair is off it and green while it is on it. An
 * optional square around the crosshair carries the same colour, so the answer is readable without
 * looking away from the middle of the screen.
 *
 * <p><b>Display only.</b> Nothing here moves the camera, snaps, smooths or nudges the aim. The
 * player moves the mouse; this says where the target is and whether the crosshair is on it.
 *
 * <p><b>Visible only.</b> Drawn only while the crosshair is on the block that carries the target,
 * only when the target's face points toward the camera, and only when a ray from the camera to the
 * marker reaches it without crossing another block. The world overlay itself has no depth buffer,
 * so that ray is the depth test - nothing is ever drawn through a wall.
 */
public final class PrecisionMiningRender {

    /** Lifts the marker off the face so it does not sit exactly in the block's plane. */
    private static final double LIFT = 0.002;
    private static final int FILL_ALPHA = 0xA0;
    /** Half the edge of the square drawn around the crosshair, in GUI pixels. */
    private static final int RING_HALF = 6;

    /** What the last frame decided, for the HUD line. */
    private static long shownAt;
    private static boolean onTarget;

    private PrecisionMiningRender() {
    }

    /** Called from the HUD world-render pass once per frame, beside the Pickobolus preview. */
    public static void render(GuiGraphicsExtractor g) {
        MiningHelpersSettings cfg = ConfigManager.getInstance().get().miningHelpers;
        if (!cfg.enabled || !cfg.precisionTarget || !SkillIslands.miningAllowed()) {
            return;
        }
        long now = System.currentTimeMillis();
        PrecisionTarget.Point target = PrecisionMiningTracker.getInstance().current(now);
        if (target == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null
                || !(minecraft.hitResult instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK) {
            return;
        }
        BlockPos hitPos = hit.getBlockPos();
        if (hitPos.getX() != target.bx() || hitPos.getY() != target.by() || hitPos.getZ() != target.bz()) {
            return;   // the crosshair left the block: the target is that block's, so it is not drawn
        }

        Camera camera = minecraft.gameRenderer.mainCamera();
        Vec3 camPos = camera.position();
        PrecisionTarget.Face face = target.face();
        Vec3 centre = onFace(target).add(face.nx * LIFT, face.ny * LIFT, face.nz * LIFT);
        if (!visible(level, player, camPos, centre, face, hitPos)) {
            return;
        }

        double radius = cfg.precisionRadius / 100.0;
        Vec3 aim = hit.getLocation();
        boolean on = PrecisionTarget.onTarget(target, hitPos.getX(), hitPos.getY(), hitPos.getZ(),
                aim.x, aim.y, aim.z, radius);
        shownAt = now;
        onTarget = on;

        int rgb = on ? cfg.precisionOnRgb() : cfg.precisionOffRgb();
        Matrix4f viewProjection = camera.getViewRotationProjectionMatrix(new Matrix4f());
        drawSquare(g, viewProjection, camPos, centre, face, cfg.precisionMarkerSize / 200.0, rgb);
        if (cfg.precisionCrosshairRing) {
            drawRing(g, 0xFF000000 | rgb);
        }
    }

    /**
     * Whether the HUD line should read "on target", or {@code null} when no marker was drawn in the
     * last quarter second.
     */
    public static Boolean onTargetNow(long now) {
        return now - shownAt > 250L ? null : onTarget;
    }

    /** The particle's position pressed flat onto its face's plane. */
    private static Vec3 onFace(PrecisionTarget.Point target) {
        double x = target.x();
        double y = target.y();
        double z = target.z();
        switch (target.face()) {
            case DOWN -> y = target.by();
            case UP -> y = target.by() + 1;
            case NORTH -> z = target.bz();
            case SOUTH -> z = target.bz() + 1;
            case WEST -> x = target.bx();
            case EAST -> x = target.bx() + 1;
        }
        return new Vec3(x, y, z);
    }

    /**
     * The face points toward the camera and nothing stands between the camera and the marker. The
     * ray stops just short of the marker, so the target block itself never counts as in the way.
     */
    private static boolean visible(ClientLevel level, LocalPlayer player, Vec3 camPos, Vec3 centre,
                                   PrecisionTarget.Face face, BlockPos targetPos) {
        double toCamera = (camPos.x - centre.x) * face.nx + (camPos.y - centre.y) * face.ny
                + (camPos.z - centre.z) * face.nz;
        if (toCamera <= 0) {
            return false;
        }
        Vec3 nearEnd = centre.add(face.nx * 0.01, face.ny * 0.01, face.nz * 0.01);
        BlockHitResult ray = level.clip(new ClipContext(camPos, nearEnd,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return ray.getType() != HitResult.Type.BLOCK || ray.getBlockPos().equals(targetPos);
    }

    /** A filled square of half-edge {@code half} lying on {@code face}, centred on {@code centre}. */
    private static void drawSquare(GuiGraphicsExtractor g, Matrix4f viewProjection, Vec3 camPos,
                                   Vec3 centre, PrecisionTarget.Face face, double half, int rgb) {
        Vec3 u;
        Vec3 v;
        switch (face) {
            case DOWN, UP -> {
                u = new Vec3(half, 0, 0);
                v = new Vec3(0, 0, half);
            }
            case NORTH, SOUTH -> {
                u = new Vec3(half, 0, 0);
                v = new Vec3(0, half, 0);
            }
            default -> {
                u = new Vec3(0, 0, half);
                v = new Vec3(0, half, 0);
            }
        }
        Vec3 a = centre.subtract(u).subtract(v);
        Vec3 b = centre.add(u).subtract(v);
        Vec3 c = centre.add(u).add(v);
        Vec3 d = centre.subtract(u).add(v);
        WorldRender.fillQuad(g, viewProjection, camPos, a, b, c, d, (FILL_ALPHA << 24) | rgb, 1);
        int edge = 0xFF000000 | rgb;
        WorldRender.line3d(g, viewProjection, camPos, a, b, edge, 1);
        WorldRender.line3d(g, viewProjection, camPos, b, c, edge, 1);
        WorldRender.line3d(g, viewProjection, camPos, c, d, edge, 1);
        WorldRender.line3d(g, viewProjection, camPos, d, a, edge, 1);
    }

    /** A one-pixel square around the crosshair. */
    private static void drawRing(GuiGraphicsExtractor g, int argb) {
        int cx = g.guiWidth() / 2;
        int cy = g.guiHeight() / 2;
        int r = RING_HALF;
        g.fill(cx - r, cy - r, cx + r + 1, cy - r + 1, argb);
        g.fill(cx - r, cy + r, cx + r + 1, cy + r + 1, argb);
        g.fill(cx - r, cy - r + 1, cx - r + 1, cy + r, argb);
        g.fill(cx + r, cy - r + 1, cx + r + 1, cy + r, argb);
    }
}
