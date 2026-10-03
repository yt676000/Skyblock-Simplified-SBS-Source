/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.experiment.logic;

/**
 * The part of the misclick guard that decides when to <b>stop guarding</b>.
 *
 * <p>Separate from the two solvers on purpose. The guard is the only thing in this mod that can take
 * a click away from the player, and what it costs when it is wrong is not symmetric: a misclick that
 * gets through loses one experiment, while a correct click that is eaten loses the experiment
 * <i>and</i> leaves the player unable to do anything about it. So the policy is fail-open, and
 * fail-open is exactly the behaviour a board-driven solver cannot be tested on - a live board needs
 * item components, which do not exist outside a running client. Here it is a clock and two counters,
 * and {@code MisclickValveTest} drives it frame by frame.
 */
final class MisclickValve {

    /**
     * No board activity for this long and the guard stands down: a solver that missed a flash, or
     * one looking at a board it has never seen, must degrade to "no help" and never to "you cannot
     * click".
     */
    static final long STALE_MS = 3_000L;

    /** Vetoes in a row before the valve gives up and lets the round finish by hand. */
    static final int VETO_LIMIT = 2;

    private boolean open;
    private int vetoedIndex = -1;
    private int vetoes;

    /** Whether the guard has given up for this round: every click goes through from here. */
    boolean open() {
        return open;
    }

    /** New round / new phase: guard again. */
    void reset() {
        open = false;
        vetoedIndex = -1;
        vetoes = 0;
    }

    /** A click was let through - whatever disagreement there was, it is over. */
    void allowed() {
        vetoedIndex = -1;
        vetoes = 0;
    }

    /**
     * Records a refused click. Returns {@code true} once the valve has opened, i.e. the caller
     * should stand down for the rest of the round instead of refusing again.
     */
    boolean veto(int index) {
        // ANY two refusals in a row, not two of the same tile. Ultrasequencer used to require the
        // player to insist on one slot, and a player whose click is refused almost never does that -
        // they assume they misread the board and try a different tile, which reset the counter every
        // time. The way out existed and was unreachable, which is the same as not having one.
        vetoedIndex = index;
        if (++vetoes >= VETO_LIMIT) {
            open = true;
            return true;
        }
        return false;
    }

    /**
     * Whether the board has gone quiet: both the current phase and the last flash are older than
     * {@link #STALE_MS}.
     *
     * @param now           the current time
     * @param phaseEnteredAt when the solver last changed phase
     * @param lastFlashAt   when a tile last lit up, or 0 if none has
     */
    static boolean idle(long now, long phaseEnteredAt, long lastFlashAt) {
        return now - phaseEnteredAt > STALE_MS
                && (lastFlashAt == 0 || now - lastFlashAt > STALE_MS);
    }
}
