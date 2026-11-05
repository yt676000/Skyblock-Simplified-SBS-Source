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

    /** Default vetoes in a row before the valve gives up and lets the round finish by hand. */
    static final int VETO_LIMIT = 2;

    /**
     * A refusal of the SAME slot this soon after the previous one is the same gesture, not a second
     * attempt: a double-click on a wrong tile. Counting it opened the valve on the spot and let that
     * very misclick through - the guard refused once, invisibly, and the round ended anyway.
     */
    static final long REPEAT_MS = 400L;

    private int vetoLimit = VETO_LIMIT;
    private long staleMs = STALE_MS;

    private boolean open;
    private int vetoedIndex = -1;
    private long vetoedAt = Long.MIN_VALUE / 2;
    private int vetoes;

    /**
     * Applies the player's settings (defaults {@link #VETO_LIMIT}, {@link #STALE_MS}). Clamped so
     * the valve can never be configured shut: the limit is at least 1, the board timeout at least
     * half a second.
     */
    void configure(int limit, long stale) {
        vetoLimit = Math.max(1, limit);
        staleMs = Math.max(500L, stale);
    }

    /** Whether the guard has given up for this round: every click goes through from here. */
    boolean open() {
        return open;
    }

    /** Refusals in the current streak. */
    int vetoes() {
        return vetoes;
    }

    /** New round / new phase: guard again. */
    void reset() {
        open = false;
        vetoedIndex = -1;
        vetoedAt = Long.MIN_VALUE / 2;
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
        vetoedAt = Long.MIN_VALUE / 2;   // no clock: never inside the repeat window
        return veto(index, Long.MIN_VALUE / 4);
    }

    /**
     * {@link #veto(int)} at {@code now}: a repeat of the slot just refused, within
     * {@link #REPEAT_MS}, is refused again without counting.
     */
    boolean veto(int index, long now) {
        if (index == vetoedIndex && now - vetoedAt >= 0 && now - vetoedAt < REPEAT_MS) {
            vetoedAt = now;
            return false;
        }
        // ANY refusals in a row, not repeats of the same tile. Ultrasequencer used to require the
        // player to insist on one slot, and a player whose click is refused almost never does that -
        // they assume they misread the board and try a different tile, which reset the counter every
        // time. The way out existed and was unreachable, which is the same as not having one.
        vetoedIndex = index;
        vetoedAt = now;
        if (++vetoes >= vetoLimit) {
            open = true;
            return true;
        }
        return false;
    }

    /** {@link #idle} with this valve's configured timeout. */
    boolean quiet(long now, long phaseEnteredAt, long lastFlashAt) {
        return idle(now, phaseEnteredAt, lastFlashAt, staleMs);
    }

    /**
     * Whether a container input on {@code slotId} is one the guard judges: the menu's own slots,
     * never the player inventory below them (36 slots) and never a click outside the window (-999).
     * The slot id is vanilla's own resolution of the click, so nothing drawn over the board can
     * hide the slot from the guard.
     */
    static boolean onBoard(int slotId, int menuItemCount) {
        return slotId >= 0 && slotId < Math.max(0, menuItemCount - 36);
    }

    /** For the per-click log line. */
    String describe(long now) {
        return "open=" + open + " vetoes=" + vetoes + "/" + vetoLimit + " staleMs=" + staleMs
                + " sinceVeto=" + (vetoedIndex < 0 ? "-" : String.valueOf(now - vetoedAt));
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
        return idle(now, phaseEnteredAt, lastFlashAt, STALE_MS);
    }

    /** {@link #idle(long, long, long)} with an explicit timeout. */
    static boolean idle(long now, long phaseEnteredAt, long lastFlashAt, long stale) {
        return now - phaseEnteredAt > stale
                && (lastFlashAt == 0 || now - lastFlashAt > stale);
    }
}
