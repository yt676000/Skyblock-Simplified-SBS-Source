/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.run.logic;

import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import sbs.modid.client.dungeons.rooms.DungeonRoom;

/**
 * Room identification by its <b>core</b>: a hash over the block column in the centre of one room
 * cell. The centre column never moves when Hypixel rotates a room, and one cell is enough - so the
 * name is known even while the footprint is still wrong or only partly revealed (the 1x1 "Mini Rail
 * Track" fused into a brown L by the map). The core only names the room; facing, borders and
 * secrets still come from the signature-block match.
 *
 * <p>Blocks that change during a run (opened chests, pistons, liquids, fire) and every plank variant
 * count as air, so looted or redstone-driven rooms hash the same as fresh ones.
 */
public final class RoomCoreHash {

    /** Top of the hashed column (inclusive). */
    static final int TOP_Y = 140;
    /** Bottom of the hashed column (inclusive). */
    static final int BOTTOM_Y = 12;
    /** Offset from a cell's NW corner to its centre column (cells span corner..corner+30). */
    static final int CENTRE_OFFSET = 15;

    private static final String AIR = "minecraft:air";
    private static final Set<String> VOLATILE = Set.of(
            "minecraft:chest", "minecraft:trapped_chest",
            "minecraft:piston_head", "minecraft:moving_piston",
            "minecraft:water", "minecraft:lava",
            "minecraft:fire", "minecraft:soul_fire");

    private RoomCoreHash() {
    }

    /** The hash of the column at the centre of the cell holding {@code (x, z)}. */
    public static int coreAt(DungeonRoomMatcher.BlockLookup lookup, int x, int z) {
        int cx = DungeonRoomLocator.cornerCoord(x) + CENTRE_OFFSET;
        int cz = DungeonRoomLocator.cornerCoord(z) + CENTRE_OFFSET;
        int hash = 1;
        for (int y = TOP_Y; y >= BOTTOM_Y; y--) {
            hash = 31 * hash + token(lookup.idAt(new BlockPos(cx, y, cz))).hashCode();
        }
        return hash;
    }

    /** A block id as it enters the hash: volatile blocks and planks collapse to air. */
    static String token(String blockId) {
        if (blockId == null || VOLATILE.contains(blockId) || blockId.endsWith("_planks")) {
            return AIR;
        }
        return blockId;
    }

    /** The database room whose {@code cores} contain {@code core}, or {@code null}. */
    public static String roomFor(int core, Map<String, DungeonRoom> database) {
        for (Map.Entry<String, DungeonRoom> entry : database.entrySet()) {
            if (entry.getValue().cores != null && entry.getValue().cores.contains(core)) {
                return entry.getKey();
            }
        }
        return null;
    }
}
