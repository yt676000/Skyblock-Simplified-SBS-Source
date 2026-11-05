/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.blood.logic;

/**
 * The camp clock and the mark it predicts, with no game state in it: every call is handed the wall
 * time and the measured tick rate, so the arithmetic of {@link BloodMoveTimer} can be tested on a
 * scripted room.
 *
 * <p><b>The clock and the prediction are separate.</b> The clock runs whenever a cycle is open; a
 * mark is only worked out, shown and fired when the caller passes {@code predict}. With prediction
 * off the gap is still measured - it is the number the re-measurement needs - but nothing is called.
 *
 * <p>The constants are the Watcher's timing <b>as it was before Hypixel's 2026-10 dialogue patch</b>
 * and are not trusted until re-measured; see {@link BloodMoveTimer#TIMING_VERIFIED}.
 */
final class BloodCampClock {

    /** The Watcher's checks sit on a three-second grid, counted from his opening line. */
    static final double GRID_SECONDS = 3.0;

    /** The earliest check worth aiming at - the room is not killable before it. */
    static final double FIRST_MARK_SECONDS = 24.0;

    /** The last one that still skips anything; past it he has said his piece anyway. */
    static final double LAST_MARK_SECONDS = 36.0;

    /** How much room a mark needs to be worth calling: any less and you cannot finish the mobs. */
    static final double LEAD_SECONDS = 2.0;

    /** The flash lands a touch early, so the last hit falls on the mark instead of just behind it. */
    static final double ALERT_LEAD_SECONDS = 0.15;

    /** Shorter than this and he was still talking when he cued - that is not a cycle to measure. */
    static final double MIN_GAP_SECONDS = 1.0;

    /** Longer than this and the clock is not measuring this room's start. */
    static final double MAX_GAP_SECONDS = 34.0;

    /** A clock this old belongs to a room that ended without saying so. */
    static final double STALE_SECONDS = 90.0;

    /** Tick rates outside this band are a freeze or a world change, not a room running slow. */
    static final double MIN_TPS = 5.0;
    static final double FULL_TPS = 20.0;

    /** What {@link #cue} found: the measured gap, and the mark it points at (0 when none). */
    enum CueResult {
        /** No cycle was open, so there was nothing to measure. */
        NOT_RUNNING,
        /** Gap measured; prediction is off, so no mark was set. */
        MEASURED_ONLY,
        /** Gap outside the band - the opening line was missed or belongs to another room. */
        GAP_OUT_OF_RANGE,
        /** A mark is set and will fire. */
        MARKED
    }

    /** When the cycle's opening line landed (wall clock); 0 while no clock is running. */
    private volatile long openedAt;

    /** Server-seconds since that line. */
    private volatile double elapsed;

    /** Wall clock of the last step, i.e. what {@link #advance} last converted. */
    private long lastStep;

    /** The grid mark to call out at, in server-seconds since the opening line; 0 while none is due. */
    private volatile double mark;

    /** The measured opening-to-cue gap of this cycle, or -1 while nothing was measured. */
    private volatile double gap = -1;

    private volatile boolean fired;

    /**
     * He spoke: the clock starts over from here - every cycle, not just the first. A call that is
     * already standing is not moved: it was measured off a gap that really happened, and a stray
     * taunt mid-fight must not throw it away seconds before it comes due.
     */
    void start(long now) {
        if (mark != 0 && !fired) {
            return;
        }
        openedAt = now;
        lastStep = now;
        elapsed = 0;
        mark = 0;
        gap = -1;
        fired = false;
    }

    /**
     * The reference line: measures the gap and, when {@code predict}, sets the mark it points at.
     * The clock is caught up to this instant rather than to the last tick - a fifty-millisecond error
     * is a third of the whole call-out lead.
     */
    CueResult cue(long now, double tps, boolean predict) {
        if (openedAt == 0) {
            return CueResult.NOT_RUNNING;
        }
        advance(now, tps);
        double measured = elapsed;
        gap = measured;
        if (!predict) {
            mark = 0;
            return CueResult.MEASURED_ONLY;
        }
        if (measured < MIN_GAP_SECONDS || measured >= MAX_GAP_SECONDS) {
            mark = 0;
            return CueResult.GAP_OUT_OF_RANGE;
        }
        mark = markAfter(measured);
        fired = false;
        return CueResult.MARKED;
    }

    /**
     * Advances the clock and answers whether the call-out fires on this tick. Never true without
     * {@code predict}; a stale clock clears itself.
     */
    boolean tick(long now, double tps, boolean predict) {
        if (openedAt == 0) {
            return false;
        }
        advance(now, tps);
        if (elapsed > STALE_SECONDS) {
            clear(); // a room that ended without its closing line must not time the next one
            return false;
        }
        if (!predict || mark == 0 || fired || elapsed < mark - ALERT_LEAD_SECONDS) {
            return false;
        }
        fired = true;
        return true;
    }

    /** Milliseconds until the mark, or -1 when nothing is predicted right now. */
    long etaMs(boolean predict) {
        double due = mark;
        if (!predict || due == 0 || openedAt == 0) {
            return -1;
        }
        double left = due - elapsed;
        return left <= 0 ? -1 : (long) (left * 1000.0);
    }

    /**
     * The mark to aim at: the first point of his grid that is still far enough away to finish the
     * room before it, never earlier than his first check and never past his last.
     */
    static double markAfter(double measured) {
        double earliest = measured + LEAD_SECONDS;
        double next = (Math.floor(earliest / GRID_SECONDS) + 1) * GRID_SECONDS;
        return Math.min(LAST_MARK_SECONDS, Math.max(FIRST_MARK_SECONDS, next));
    }

    /**
     * Moves the clock up to {@code now}, converting real time into server time. The rate is sampled
     * every step, because a room stalls while the mobs come in and recovers afterwards, and only the
     * stalling part should stretch the count.
     */
    private void advance(long now, double tps) {
        long stepMs = now - lastStep;
        lastStep = now;
        if (stepMs > 0) {
            elapsed += serverSeconds(stepMs, tps);
        }
    }

    /** {@code stepMs} of wall time as server seconds at {@code tps}; real time when the rate is unusable. */
    static double serverSeconds(long stepMs, double tps) {
        return stepMs / 1000.0 * tickFactor(tps);
    }

    /** Server seconds per real second: 1.0 whenever there is no trustworthy reading to use. */
    static double tickFactor(double tps) {
        return tps < MIN_TPS || tps > FULL_TPS ? 1.0 : tps / FULL_TPS;
    }

    void clear() {
        openedAt = 0;
        elapsed = 0;
        mark = 0;
        gap = -1;
        fired = false;
    }

    boolean running() {
        return openedAt != 0;
    }

    double elapsed() {
        return elapsed;
    }

    double gap() {
        return gap;
    }

    double mark() {
        return mark;
    }

    boolean fired() {
        return fired;
    }
}
