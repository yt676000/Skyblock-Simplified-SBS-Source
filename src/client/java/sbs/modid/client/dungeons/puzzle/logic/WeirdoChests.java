/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.puzzle.logic;

import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pairs each Three Weirdos NPC with the chest it stands beside, as a pure function of positions.
 *
 * <p>The room database records the three chests as blocks, not as named waypoints, so which chest
 * belongs to which NPC is decided from the layout: each NPC stands next to its own chest. A pair is
 * accepted only when it is <b>mutual</b> - the chest is the NPC's nearest, and the NPC is the chest's
 * nearest among the NPCs located - and close. Anything else (a tie, a chest nearer to a different
 * NPC, a chest across the room) leaves that NPC unpaired, and an unpaired winner draws nothing.
 */
public final class WeirdoChests {

    /**
     * How far an NPC may stand from its chest, horizontally, in blocks. The pairing is "standing
     * beside it"; three blocks is that with room for the NPC's position being read off an entity
     * rather than a block. {@code ESTIMATED} - the real spacing has not been measured in 26.x.
     */
    static final int MAX_HORIZONTAL = 3;

    /** How far apart vertically an NPC and its chest may be: both stand on the room floor, and the
     * NPC may be located by a name tag floating above its head. */
    static final int MAX_VERTICAL = 3;

    private WeirdoChests() {
    }

    /**
     * The chest each NPC stands beside, for every NPC whose pairing is unambiguous.
     *
     * @param npcs   speaker name to the block the NPC stands in
     * @param chests every chest found in the room, in any order
     * @return name to chest; NPCs that could not be paired with certainty are absent
     */
    public static Map<String, BlockPos> pair(Map<String, BlockPos> npcs, List<BlockPos> chests) {
        Map<String, BlockPos> out = new HashMap<>();
        if (npcs == null || chests == null || npcs.isEmpty() || chests.isEmpty()) {
            return out;
        }
        for (Map.Entry<String, BlockPos> npc : npcs.entrySet()) {
            BlockPos chest = uniqueNearest(npc.getValue(), chests);
            if (chest == null || !inReach(npc.getValue(), chest)) {
                continue;
            }
            // Mutual: no other located NPC is as close to this chest.
            boolean mutual = true;
            int own = distanceSq(npc.getValue(), chest);
            for (Map.Entry<String, BlockPos> other : npcs.entrySet()) {
                if (!other.getKey().equals(npc.getKey()) && distanceSq(other.getValue(), chest) <= own) {
                    mutual = false;
                    break;
                }
            }
            if (mutual) {
                out.put(npc.getKey(), chest);
            }
        }
        return out;
    }

    /** The single nearest chest, or {@code null} when two are equally near. */
    private static BlockPos uniqueNearest(BlockPos from, List<BlockPos> chests) {
        BlockPos best = null;
        int bestDistance = Integer.MAX_VALUE;
        boolean tie = false;
        for (BlockPos chest : chests) {
            int distance = distanceSq(from, chest);
            if (distance < bestDistance) {
                best = chest;
                bestDistance = distance;
                tie = false;
            } else if (distance == bestDistance) {
                tie = true;
            }
        }
        return tie ? null : best;
    }

    private static boolean inReach(BlockPos npc, BlockPos chest) {
        return Math.abs(npc.getX() - chest.getX()) <= MAX_HORIZONTAL
                && Math.abs(npc.getZ() - chest.getZ()) <= MAX_HORIZONTAL
                && Math.abs(npc.getY() - chest.getY()) <= MAX_VERTICAL;
    }

    private static int distanceSq(BlockPos a, BlockPos b) {
        int dx = a.getX() - b.getX();
        int dy = a.getY() - b.getY();
        int dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }
}
