/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.pathfinding;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * The three questions the pathfinder asks the world. An interface so {@link PathfinderTask} can be
 * run on a hand-built grid in unit tests - the pad and no-route decisions are search logic and are
 * tested as such, not in a live world.
 */
interface Terrain {

    /** Whether a player standing at {@code (x, y, z)} would be supported and fit. */
    boolean canStand(int x, int y, int z);

    /** Whether the two blocks a player occupies at {@code (x, y, z)} are free. */
    boolean bodyClear(int x, int y, int z);

    /** Whether one block is free to move through. */
    boolean isPassable(int x, int y, int z);

    /**
     * {@link Walkability#STAND}, {@link Walkability#SNEAK} or {@link Walkability#NO_STAND}. A grid
     * that knows nothing about headroom answers from {@link #canStand}: standing or nothing.
     */
    default int standKind(int x, int y, int z) {
        return canStand(x, y, z) ? Walkability.STAND : Walkability.NO_STAND;
    }

    /** The live world, through {@link Walkability} exactly as before. */
    static Terrain of(Level level) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        // Read once per search, not per block: the setting cannot change mid-search meaningfully.
        boolean allowSneak = Walkability.allowSneak();
        return new Terrain() {
            @Override
            public boolean canStand(int x, int y, int z) {
                return Walkability.canStand(level, cursor, x, y, z);
            }

            @Override
            public boolean bodyClear(int x, int y, int z) {
                return Walkability.bodyClear(level, cursor, x, y, z);
            }

            @Override
            public boolean isPassable(int x, int y, int z) {
                return Walkability.isPassable(level, cursor, x, y, z);
            }

            @Override
            public int standKind(int x, int y, int z) {
                return Walkability.standKind(level, cursor, x, y, z, allowSneak);
            }
        };
    }
}
