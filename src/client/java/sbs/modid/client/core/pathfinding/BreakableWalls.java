/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.pathfinding;

/**
 * Which walls a route may go <i>through</i>: dungeon weak walls and crypts (Superboom TNT), and the
 * breakable walls in the Hub and the Park. <b>Not implemented yet</b> - only {@link #NONE} exists, and
 * with it the search behaves exactly as before. See "Not done yet" in
 * {@code docs/features/hybrid-pathfinding.md} for where the data would come from.
 *
 * <p>The search asks {@link #canBreak()} once per node and {@link #breakable} only for blocks that
 * actually stop a step, so a provider may be as slow as a map lookup.
 */
public interface BreakableWalls {

    /** No breakable walls anywhere - the default, and the only provider today. */
    BreakableWalls NONE = new BreakableWalls() {
        @Override
        public boolean breakable(int x, int y, int z) {
            return false;
        }

        @Override
        public boolean canBreak() {
            return false;
        }
    };

    /** Whether the block at {@code (x, y, z)} is a wall that can be opened here. */
    boolean breakable(int x, int y, int z);

    /** Whether the player can open walls right now (e.g. Superboom TNT in the inventory). */
    boolean canBreak();
}
