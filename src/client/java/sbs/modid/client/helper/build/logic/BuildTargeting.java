/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.build.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import sbs.modid.client.core.keybind.Keys;
import sbs.modid.client.helper.build.model.CameraRay;

/**
 * The one answer to "which block is the player selecting": in freecam a ray from the camera, else
 * the player's crosshair - moved {@code depth} blocks further along the ray by Alt+wheel ("step
 * deeper"), so a block inside a wall can be picked.
 *
 * <p>The vanilla crosshair hit ({@code Minecraft.hitResult}) is only ever read, never changed: what
 * the player's hand would act on stays exactly the game's.
 */
public final class BuildTargeting {

    /** A target: the block, and the face it was looked at through. */
    public record Target(BlockPos pos, Direction face) {
    }

    private static int depth;
    private static BlockPos depthBase;

    private BuildTargeting() {
    }

    /** Whether Alt+wheel steps deeper right now: build input ({@link BuildInput}) or a corner key held. */
    public static boolean depthApplies() {
        return BuildInput.active() || cornerKeyHeld();
    }

    /** Whether a corner key is held - the target preview shows while it is. */
    public static boolean cornerKeyHeld() {
        var cfg = BuildToolsOwner.cfg();
        return (cfg.corner1Key != 0 && Keys.isDown(cfg.corner1Key)) || (cfg.corner2Key != 0 && Keys.isDown(cfg.corner2Key));
    }

    public static void stepDepth(int delta) {
        depth = Math.max(0, Math.min(32, depth + delta));
    }

    public static int depth() {
        return depth;
    }

    public static void resetDepth() {
        depth = 0;
        depthBase = null;
    }

    /** The targeted block, or {@code null} when nothing is in reach. */
    public static Target target() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null) {
            return null;
        }
        CameraRay.Hit first;
        if (Freecam.active()) {
            first = Freecam.ray(0);
        } else {
            HitResult hit = minecraft.hitResult;
            if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
                return null;
            }
            if (depth == 0) {
                return new Target(blockHit.getBlockPos(), blockHit.getDirection());
            }
            BlockPos base = blockHit.getBlockPos();
            first = new CameraRay.Hit(base.getX(), base.getY(), base.getZ(), 0, 0, 0);
        }
        if (first == null) {
            return null;
        }
        BlockPos base = new BlockPos(first.x(), first.y(), first.z());
        if (!base.equals(depthBase)) {
            // Looking at a different block starts over at its face.
            depthBase = base;
            depth = 0;
        }
        if (depth == 0) {
            return new Target(base, face(first));
        }
        Vec3 origin = Freecam.active() ? Freecam.position(1f) : player.getEyePosition();
        Vec3 look = Freecam.active() ? Freecam.look() : player.getLookAngle();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        CameraRay.Hit deeper = CameraRay.cast(origin.x, origin.y, origin.z, look.x, look.y, look.z,
                Math.max(8, Math.min(128, BuildToolsOwner.cfg().rayDistance)), depth,
                (bx, by, bz) -> !level.getBlockState(pos.set(bx, by, bz)).getShape(level, pos).isEmpty());
        return deeper == null ? new Target(base, face(first))
                : new Target(new BlockPos(deeper.x(), deeper.y(), deeper.z()), face(deeper));
    }

    private static Direction face(CameraRay.Hit hit) {
        if (hit.faceX() != 0) {
            return hit.faceX() > 0 ? Direction.EAST : Direction.WEST;
        }
        if (hit.faceZ() != 0) {
            return hit.faceZ() > 0 ? Direction.SOUTH : Direction.NORTH;
        }
        return hit.faceY() < 0 ? Direction.DOWN : Direction.UP;
    }
}
