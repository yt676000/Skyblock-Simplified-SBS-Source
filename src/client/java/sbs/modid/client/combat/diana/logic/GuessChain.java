/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.diana.logic;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * One arrow's worth of candidate burrows, in the order they should be walked.
 *
 * <h2>Why an ordered list and not one answer</h2>
 *
 * <p>A ray cast down a long axis genuinely has several equally good answers. Blocks a hundred blocks
 * apart can sit the same angular distance from the line, and the scoring cannot separate them
 * because there is nothing left in the signal to separate them with. Collapsing that to one block
 * would be inventing certainty; discarding the lot would be throwing away a correct answer that is
 * second in the list.
 *
 * <p>So the honest structure is a queue: the front one is the live guess, the rest are shown faintly
 * if the player asks for them, and walking to a wrong one advances the queue rather than ending the
 * feature.
 *
 * <h2>Advancing</h2>
 *
 * <p>A candidate is abandoned when its block stops being a plausible burrow, or when the player
 * carrying a spade has got close enough that a real burrow would have announced itself and none has.
 * Both are handled by the owner; this class only knows how to step forward and when it has run out.
 */
public final class GuessChain {

    private final List<BlockPos> candidates;

    /** How far down {@link #candidates} the live guess currently is. */
    private int index;

    /** Digs the player has put into the current candidate, carried across if it turns out real. */
    private int digs;

    /** When this chain was built, so a stale one can be dropped. */
    private final long createdAt = System.currentTimeMillis();

    public GuessChain(List<BlockPos> candidates) {
        this.candidates = new ArrayList<>(candidates);
    }

    /** The block the player should walk to, or {@code null} once the chain is spent. */
    public BlockPos current() {
        return index < candidates.size() ? candidates.get(index) : null;
    }

    /** The runners-up, in order. Empty once the last candidate is live. */
    public List<BlockPos> remaining() {
        if (index + 1 >= candidates.size()) {
            return List.of();
        }
        return List.copyOf(candidates.subList(index + 1, candidates.size()));
    }

    /** Whether this chain has anything left to point at. */
    public boolean spent() {
        return index >= candidates.size();
    }

    public long createdAt() {
        return createdAt;
    }

    /** Digs recorded against the live candidate, for carrying across a promotion. */
    public int digs() {
        return digs;
    }

    /** The player dug the live candidate. */
    public void recordDig() {
        digs++;
    }

    /**
     * Gives up on the live candidate and moves to the next.
     *
     * <p>The dig count resets with the candidate: it counted digs into a block that has just been
     * ruled out, and carrying it onto a different block would have the next marker vanish after one
     * dig for reasons belonging to a block the player has walked away from.
     *
     * @return whether there was another candidate to move to
     */
    public boolean advance() {
        index++;
        digs = 0;
        return !spent();
    }

    /** Whether any candidate in this chain - live or not - is at {@code pos}. */
    public boolean contains(BlockPos pos) {
        return pos != null && candidates.contains(pos);
    }

    /** Whether the live candidate is at {@code pos}. */
    public boolean pointsAt(BlockPos pos) {
        BlockPos live = current();
        return live != null && live.equals(pos);
    }

    /**
     * Whether this chain says the same thing as another - same remaining candidates, same order.
     *
     * <p>Arrows are re-fitted as more particles arrive, and the same arrow fitted twice produces the
     * same answer. Without this the player would collect a fresh chain every few packets and the
     * markers would be rebuilt underneath them.
     */
    public boolean sameAs(GuessChain other) {
        if (other == null) {
            return false;
        }
        List<BlockPos> mine = candidates.subList(Math.min(index, candidates.size()), candidates.size());
        List<BlockPos> theirs = other.candidates.subList(
                Math.min(other.index, other.candidates.size()), other.candidates.size());
        return mine.equals(theirs);
    }

    /** How many candidates this chain started with. For the debug readout. */
    public int size() {
        return candidates.size();
    }

    @Override
    public String toString() {
        BlockPos live = current();
        return (live == null ? "spent" : live.getX() + " " + live.getY() + " " + live.getZ())
                + " (" + (index + 1) + "/" + candidates.size() + ")";
    }
}
