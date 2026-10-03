/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.run.logic;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

/**
 * Finds dungeon doorways by their exact air geometry. Doors are never diagonal – they are always
 * axis-aligned, so only the two horizontal axes are tested.
 *
 * <p>Exactly two door shapes exist (width x height x depth of <b>air</b>):
 * <ul>
 *   <li><b>3x4x5</b> – the full passage is 4 high over all 5 depth layers;</li>
 *   <li><b>3x3x1 + 3x4x3 + 3x3x1</b> – the two mouth layers are only 3 high, the middle 3 layers
 *       are 4 high.</li>
 * </ul>
 * Anything else is not a door. Both shapes share the same base test: a 3x3x5 air tunnel whose middle
 * three layers are air at y=3 as well; the mouth layers' y=3 cells are simply not constrained (air ⇒
 * first shape, blocked ⇒ second shape).
 *
 * <p>To reject open room air the pattern also demands the enclosing shell: a solid lid at y=4 above the
 * middle layers (height is exactly 4), solid side walls at w=±2 along <b>all five</b> depth layers
 * (width is exactly 3, and a candidate shifted along the tunnel would poke its wall test into open room
 * air and fail – this pins the anchor on the depth axis), and a solid floor under the middle three
 * layers (the anchor is walkable).
 *
 * <p>The first hit's <b>floor-centre air block</b> is the door anchor {@code (0,0,0)}. In Hypixel
 * dungeons doors always sit at Y 66–73 (the known doorway constant), which the debug output can be
 * checked against. Pure Vanilla block reads ({@link Level#getBlockState}), no Fabric API.
 */
public final class DungeonDoorScanner {

    /** The horizontal axis the doorway runs along (its depth direction). */
    public enum DoorAxis {
        X, Z
    }

    /** A found doorway: floor-centre anchor, its axis, and whether it is the full-height 3x4x5 shape. */
    public record DoorMatch(BlockPos anchor, DoorAxis axis, boolean fullHeight) {

        /** Human-readable shape of the matched doorway. */
        public String variant() {
            return fullHeight ? "3x4x5" : "3x3x1+3x4x3+3x3x1";
        }

        /** The anchor's coordinate along the door axis (to compare against the player's side). */
        public int axisCoord(BlockPos pos) {
            return axis == DoorAxis.X ? pos.getX() : pos.getZ();
        }

        /**
         * The facing that points from the door <b>into the room</b> on the given side of the door axis
         * ({@code sign > 0} = positive world direction).
         */
        public Direction into(int sign) {
            if (axis == DoorAxis.X) {
                return sign >= 0 ? Direction.EAST : Direction.WEST;
            }
            return sign >= 0 ? Direction.SOUTH : Direction.NORTH;
        }
    }

    /** How far around the player to sweep for a doorway (horizontal / vertical, in blocks). */
    private static final int SEARCH_H = 4;
    private static final int SEARCH_V = 2;

    private DungeonDoorScanner() {
    }

    /** Sweeps around {@code around} for a doorway; returns the first match or {@code null}. */
    public static DoorMatch findDoorNear(Level level, BlockPos around) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int ox = around.getX();
        int oy = around.getY();
        int oz = around.getZ();
        for (int dy = -SEARCH_V; dy <= SEARCH_V; dy++) {
            for (int dx = -SEARCH_H; dx <= SEARCH_H; dx++) {
                for (int dz = -SEARCH_H; dz <= SEARCH_H; dz++) {
                    int ax = ox + dx;
                    int ay = oy + dy;
                    int az = oz + dz;
                    if (!isAir(level, cursor, ax, ay, az)) {
                        continue; // the anchor foot block itself must be air (cheap early reject)
                    }
                    // axis Z: width runs along X (wu = 1,0), depth along Z (du = 0,1).
                    DoorMatch match = matches(level, cursor, ax, ay, az, 1, 0, 0, 1, DoorAxis.Z);
                    if (match == null) {
                        // axis X: width runs along Z (wu = 0,1), depth along X (du = 1,0).
                        match = matches(level, cursor, ax, ay, az, 0, 1, 1, 0, DoorAxis.X);
                    }
                    if (match != null) {
                        return match;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Tests the full door pattern at anchor {@code (ax,ay,az)} for the given width unit {@code (wux,wuz)}
     * and depth unit {@code (dux,duz)}; returns the match (with its shape variant) or {@code null}.
     */
    private static DoorMatch matches(Level level, BlockPos.MutableBlockPos c, int ax, int ay, int az,
                                     int wux, int wuz, int dux, int duz, DoorAxis axis) {
        // 1) Base passage: 3 wide x 3 high x 5 deep, all air (shared by both door shapes).
        for (int d = -2; d <= 2; d++) {
            for (int w = -1; w <= 1; w++) {
                for (int y = 0; y <= 2; y++) {
                    if (!isAir(level, c, cellX(ax, w, d, wux, dux), ay + y, cellZ(az, w, d, wuz, duz))) {
                        return null;
                    }
                }
            }
        }
        // 2) Middle three layers are 4 high: the y=3 row is full-width air.
        for (int d = -1; d <= 1; d++) {
            for (int w = -1; w <= 1; w++) {
                if (!isAir(level, c, cellX(ax, w, d, wux, dux), ay + 3, cellZ(az, w, d, wuz, duz))) {
                    return null;
                }
            }
        }
        // 3) Solid lid at y=4 above the middle three layers (height is exactly 4).
        for (int d = -1; d <= 1; d++) {
            for (int w = -1; w <= 1; w++) {
                if (isAir(level, c, cellX(ax, w, d, wux, dux), ay + 4, cellZ(az, w, d, wuz, duz))) {
                    return null;
                }
            }
        }
        // 4) Solid side walls exactly 3 apart, along ALL five depth layers. This both enforces the
        //    3-wide dimension and pins the anchor on the depth axis: a candidate shifted by one layer
        //    would test a wall column inside the open room and fail.
        for (int w : new int[] {-2, 2}) {
            for (int d = -2; d <= 2; d++) {
                for (int y = 0; y <= 2; y++) {
                    if (isAir(level, c, cellX(ax, w, d, wux, dux), ay + y, cellZ(az, w, d, wuz, duz))) {
                        return null;
                    }
                }
            }
        }
        // 5) Solid floor under the middle three layers (gives the walkable anchor).
        for (int w = -1; w <= 1; w++) {
            for (int d = -1; d <= 1; d++) {
                if (isAir(level, c, cellX(ax, w, d, wux, dux), ay - 1, cellZ(az, w, d, wuz, duz))) {
                    return null;
                }
            }
        }
        // Shape variant: mouths 4 high too (3x4x5) or only 3 high (3x3x1 + 3x4x3 + 3x3x1).
        boolean fullHeight = true;
        for (int d : new int[] {-2, 2}) {
            for (int w = -1; w <= 1; w++) {
                if (!isAir(level, c, cellX(ax, w, d, wux, dux), ay + 3, cellZ(az, w, d, wuz, duz))) {
                    fullHeight = false;
                }
            }
        }
        return new DoorMatch(new BlockPos(ax, ay, az), axis, fullHeight);
    }

    private static int cellX(int ax, int w, int d, int wux, int dux) {
        return ax + w * wux + d * dux;
    }

    private static int cellZ(int az, int w, int d, int wuz, int duz) {
        return az + w * wuz + d * duz;
    }

    private static boolean isAir(Level level, BlockPos.MutableBlockPos cursor, int x, int y, int z) {
        return level.getBlockState(cursor.set(x, y, z)).isAir();
    }
}
