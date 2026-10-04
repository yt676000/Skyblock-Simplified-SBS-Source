/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.chest;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One run's reward chests as they stand in the world: which have been previewed, what each is worth,
 * and how they rank. No game state - {@link RewardChestGlow} feeds it and draws from it, and the
 * tests drive it directly.
 *
 * <p><b>Positions are fuzzy on purpose.</b> A chest is opened by clicking an armor stand, not a block
 * (2026-10-04, F1: the client sent entity interacts and no block use), and the stand that takes the
 * click is not always the same one - one Gold preview was opened through Bonzo's death nametag,
 * standing on the chest. Both stands were in the same block column (-39, 20) at different heights, so
 * two positions in one column within {@link #NEAR_Y} are the same chest, and the first one recorded
 * is kept so the box cannot wander. Horizontal neighbours are <i>not</i> merged: how closely Hypixel
 * spaces the chests on the higher floors is unrecorded, and merging two real chests would hide one.
 */
public final class RewardChestBoard {

    /** Vertical blocks within which one column's positions are one chest (stands sit 70.1-71.3). */
    private static final int NEAR_Y = 2;

    /** A previewed chest: the tier the menu was titled with and its valued contents. */
    public record Entry(String tier, DungeonChestValue.Chest chest) {
    }

    /** Previewed chests, in the order they were first previewed - the ranking's tie order. */
    private final Map<BlockPos, Entry> chests = new LinkedHashMap<>();
    /** Chests seen in the room but not previewed yet. */
    private final List<BlockPos> unknown = new ArrayList<>();
    /** Chests bought this run: never drawn again, and never re-offered as unknown. */
    private final List<BlockPos> bought = new ArrayList<>();
    private Map<BlockPos, DungeonChestRanking.Mark> marks = Map.of();
    private BlockPos lastPreviewed;

    /**
     * Records a preview of the chest at {@code pos}, or updates it (a reroll, a second look).
     *
     * @return the position the chest is stored under, which is an earlier nearby one if there is one
     */
    public BlockPos preview(BlockPos pos, String tier, DungeonChestValue.Chest chest) {
        BlockPos key = near(chests.keySet(), pos);
        if (key == null) {
            key = pos.immutable();
        }
        chests.put(key, new Entry(tier, chest));
        BlockPos stale = near(unknown, key);
        if (stale != null) {
            unknown.remove(stale);
        }
        lastPreviewed = key;
        rank();
        return key;
    }

    /**
     * The chest of {@code tier} was bought. The last previewed chest is the one, when its tier
     * agrees - buying is a click inside its preview - otherwise the only previewed chest of that
     * tier. Anything else is ambiguous and changes nothing.
     */
    public void bought(String tier) {
        BlockPos key = null;
        if (lastPreviewed != null && chests.containsKey(lastPreviewed)
                && chests.get(lastPreviewed).tier().equalsIgnoreCase(tier)) {
            key = lastPreviewed;
        } else {
            for (var entry : chests.entrySet()) {
                if (entry.getValue().tier().equalsIgnoreCase(tier)) {
                    if (key != null) {
                        return;
                    }
                    key = entry.getKey();
                }
            }
        }
        if (key == null) {
            return;
        }
        chests.remove(key);
        bought.add(key);
        rank();
    }

    /** Replaces the set of chests seen in the room, minus those already previewed or bought. */
    public void seen(Collection<BlockPos> positions) {
        unknown.clear();
        for (BlockPos pos : positions) {
            if (near(chests.keySet(), pos) == null && near(bought, pos) == null
                    && near(unknown, pos) == null) {
                unknown.add(pos.immutable());
            }
        }
    }

    public void clear() {
        chests.clear();
        unknown.clear();
        bought.clear();
        marks = Map.of();
        lastPreviewed = null;
    }

    public boolean isEmpty() {
        return chests.isEmpty() && unknown.isEmpty();
    }

    /** Previewed chests and their entries, in preview order. Read-only view. */
    public Map<BlockPos, Entry> chests() {
        return java.util.Collections.unmodifiableMap(chests);
    }

    /** The mark for a previewed chest; computed when a preview or purchase arrives, not per frame. */
    public DungeonChestRanking.Mark mark(BlockPos key) {
        return marks.get(key);
    }

    public List<BlockPos> unknown() {
        return java.util.Collections.unmodifiableList(unknown);
    }

    private void rank() {
        Map<BlockPos, DungeonChestValue.Chest> values = new LinkedHashMap<>();
        chests.forEach((pos, entry) -> values.put(pos, entry.chest()));
        marks = DungeonChestRanking.marks(values);
    }

    private static BlockPos near(Iterable<BlockPos> positions, BlockPos pos) {
        for (BlockPos other : positions) {
            if (other.getX() == pos.getX() && other.getZ() == pos.getZ()
                    && Math.abs(other.getY() - pos.getY()) <= NEAR_Y) {
                return other;
            }
        }
        return null;
    }
}
