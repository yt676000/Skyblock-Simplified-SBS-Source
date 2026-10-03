/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.dev;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Rotation / base-point math for the dungeon room scanner, using the
 * {@code actualToRelative} convention.
 *
 * <p>Dungeon rooms spawn rotated into any of the four cardinal directions. At scan time the player
 * stands exactly on the middle of the door block ({@code doorPos}), which is the mathematical origin
 * {@code (0,0,0)}. Every block / waypoint is rotated so the room is normalised <b>as if the door faced
 * NORTH</b> – independent of the room's real compass rotation, so the same physical room always yields
 * the same relative coordinates.
 *
 * <p>NORTH is the canonical reference (identity). The exact rotation matrix, applied to the
 * differences {@code (diffX, diffY, diffZ) = target − doorPos}:
 *
 * <pre>
 *   NORTH -> ( diffX, diffY,  diffZ)   // identity
 *   EAST  -> ( diffZ, diffY, -diffX)
 *   SOUTH -> (-diffZ, diffY,  diffX)
 *   WEST  -> (-diffX, diffY, -diffZ)
 * </pre>
 */
public final class RoomRotation {

    private RoomRotation() {
    }

    /**
     * Rotates a world block into door-relative coordinates (NORTH-canonical).
     *
     * @param facing  the player's horizontal look direction at the door ({@code player.getDirection()})
     * @param doorPos the door-centre block (relative origin)
     * @param target  the world block to normalise
     * @return the rotation-corrected relative position
     */
    public static BlockPos actualToRelative(Direction facing, BlockPos doorPos, BlockPos target) {
        int diffX = target.getX() - doorPos.getX();
        int diffY = target.getY() - doorPos.getY();
        int diffZ = target.getZ() - doorPos.getZ();
        return switch (facing) {
            case NORTH -> new BlockPos(diffX, diffY, diffZ);   // identity
            case EAST -> new BlockPos(diffZ, diffY, -diffX);
            case SOUTH -> new BlockPos(-diffZ, diffY, diffX);
            case WEST -> new BlockPos(-diffX, diffY, -diffZ);
            // UP / DOWN are never returned by getDirection(); fall back to identity defensively.
            default -> new BlockPos(diffX, diffY, diffZ);
        };
    }

    /**
     * Exact inverse of {@link #actualToRelative}: turns a door-relative position (from the database) back
     * into an absolute world position for the current run's door. Used for room matching and for placing
     * waypoints in the world.
     *
     * @param facing  the room's actual generated facing at {@code doorPos}
     * @param doorPos the current run's real door-centre block
     * @param relX    door-relative X (NORTH-normalised)
     * @param relY    door-relative Y
     * @param relZ    door-relative Z (NORTH-normalised)
     * @return the absolute world position
     */
    public static BlockPos relativeToActual(Direction facing, BlockPos doorPos, int relX, int relY, int relZ) {
        return switch (facing) {
            case NORTH -> doorPos.offset(relX, relY, relZ);    // identity
            case EAST -> doorPos.offset(-relZ, relY, relX);
            case SOUTH -> doorPos.offset(relZ, relY, -relX);
            case WEST -> doorPos.offset(-relX, relY, -relZ);
            default -> doorPos.offset(relX, relY, relZ);
        };
    }

    // ------------------------------------------------------------------
    // Double-precision + yaw variants (Secret Routes: pearl / etherwarp aim must survive rotation
    // pixel-exactly). Every transform below reuses the SAME linear map as the BlockPos versions
    // above, so precise positions and headings rotate consistently with the block coordinates.
    // ------------------------------------------------------------------

    /** Precise (sub-block) inverse of {@link #actualToRelative}: canonical relative position → world. */
    public static Vec3 relativeToActual(Direction facing, BlockPos anchor, double relX, double relY, double relZ) {
        double y = anchor.getY() + relY;
        return switch (facing) {
            case NORTH -> new Vec3(anchor.getX() + relX, y, anchor.getZ() + relZ);
            case EAST -> new Vec3(anchor.getX() - relZ, y, anchor.getZ() + relX);
            case SOUTH -> new Vec3(anchor.getX() + relZ, y, anchor.getZ() - relX);
            case WEST -> new Vec3(anchor.getX() - relX, y, anchor.getZ() - relZ);
            default -> new Vec3(anchor.getX() + relX, y, anchor.getZ() + relZ);
        };
    }

    /** Precise (sub-block) world position → canonical relative position (NORTH-normalised). */
    public static Vec3 actualToRelative(Direction facing, BlockPos anchor, double x, double y, double z) {
        double dx = x - anchor.getX();
        double dy = y - anchor.getY();
        double dz = z - anchor.getZ();
        return switch (facing) {
            case NORTH -> new Vec3(dx, dy, dz);
            case EAST -> new Vec3(dz, dy, -dx);
            case SOUTH -> new Vec3(-dz, dy, dx);
            case WEST -> new Vec3(-dx, dy, -dz);
            default -> new Vec3(dx, dy, dz);
        };
    }

    /** Canonical (NORTH-frame) yaw → the yaw for the room's actual generated facing. Pitch is unchanged. */
    public static float yawToActual(Direction facing, float relYaw) {
        double[] world = rotateDir(facing, yawToDir(relYaw), true);
        return Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-world[0], world[1])));
    }

    /** World yaw → the canonical (NORTH-frame) yaw to store. Exact inverse of {@link #yawToActual}. */
    public static float yawToRelative(Direction facing, float worldYaw) {
        double[] canonical = rotateDir(facing, yawToDir(worldYaw), false);
        return Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-canonical[0], canonical[1])));
    }

    /** Minecraft yaw to a horizontal direction ({@code x = -sin, z = cos}). */
    private static double[] yawToDir(float yaw) {
        double r = Math.toRadians(yaw);
        return new double[] {-Math.sin(r), Math.cos(r)};
    }

    /**
     * Rotates a horizontal direction by the room facing, using the identical linear map as the
     * position transforms ({@code toActual} mirrors {@link #relativeToActual}, otherwise
     * {@link #actualToRelative}), so a heading rotates in lockstep with the coordinates.
     */
    private static double[] rotateDir(Direction facing, double[] dir, boolean toActual) {
        double dx = dir[0];
        double dz = dir[1];
        if (toActual) {
            return switch (facing) {
                case NORTH -> new double[] {dx, dz};
                case EAST -> new double[] {-dz, dx};
                case SOUTH -> new double[] {dz, -dx};
                case WEST -> new double[] {-dx, -dz};
                default -> new double[] {dx, dz};
            };
        }
        return switch (facing) {
            case NORTH -> new double[] {dx, dz};
            case EAST -> new double[] {dz, -dx};
            case SOUTH -> new double[] {-dz, dx};
            case WEST -> new double[] {-dx, -dz};
            default -> new double[] {dx, dz};
        };
    }
}
