/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.slayer.logic;

import sbs.modid.client.combat.slayer.model.InfernoPhase;

/**
 * Which phase your Inferno fight is in and since when, worked out from two facts the slayer scan
 * already has: whether your boss is up, and whether one of its demons was seen this scan.
 *
 * <p>Nothing in the world announces a phase, so the phase is the demons: a demon tag at your fight
 * starts {@link InfernoPhase#DEMONS}, and the demons gone for {@link #DEMON_FORGET_MS} hands it back to
 * {@link InfernoPhase#BOSS}. The grace is there because a tag can miss a single scan (the stand
 * reloading, the demon walking behind the boss's own stack), and a timer that resets on every miss is
 * worse than none.
 *
 * <p>The clock counts up rather than down: how long a split lasts depends on how fast the demons die,
 * so there is no fixed length to count towards. Kept free of Minecraft so it can be tested on its own.
 */
public final class InfernoPhaseClock {

    /** How long the demons may go unseen before the split counts as over. */
    static final long DEMON_FORGET_MS = 1_500L;

    private InfernoPhase phase = InfernoPhase.NONE;
    private long phaseStartedAt;
    private long demonsSeenAt;
    /** Which split this is: 1 for the first time the boss broke up in this fight, 2 for the second. */
    private int split;

    /**
     * Feeds one scan.
     *
     * @return whether the phase changed with it
     */
    public boolean update(long now, boolean bossUp, boolean demonsSeen) {
        InfernoPhase next;
        if (!bossUp) {
            next = InfernoPhase.NONE;
        } else if (demonsSeen) {
            demonsSeenAt = now;
            next = InfernoPhase.DEMONS;
        } else if (phase == InfernoPhase.DEMONS && now - demonsSeenAt < DEMON_FORGET_MS) {
            next = InfernoPhase.DEMONS;
        } else {
            next = InfernoPhase.BOSS;
        }
        if (next == phase) {
            return false;
        }
        if (next == InfernoPhase.NONE) {
            split = 0;
        } else if (next == InfernoPhase.DEMONS) {
            split++;
        }
        // Leaving the demons, the clock restarts where they were last seen, not where the grace ran
        // out - the boss was back in the fight from then on.
        phaseStartedAt = phase == InfernoPhase.DEMONS && next == InfernoPhase.BOSS ? demonsSeenAt : now;
        phase = next;
        return true;
    }

    public InfernoPhase phase() {
        return phase;
    }

    /** Milliseconds spent in the current phase, or 0 while there is no fight. */
    public long elapsed(long now) {
        return phase == InfernoPhase.NONE ? 0L : Math.max(0L, now - phaseStartedAt);
    }

    /** The current or most recent split's number, 0 before the first one. */
    public int split() {
        return split;
    }
}
