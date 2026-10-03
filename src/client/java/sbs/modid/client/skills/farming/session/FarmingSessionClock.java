/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.farming.session;

/**
 * When a farming session starts, how long it was actually spent farming, and when it ends. Pure: it
 * is fed timestamps and two facts (a crop was broken; whether the player is on a farming island), so
 * the whole lifecycle is unit-tested on synthetic sequences.
 *
 * <ul>
 *   <li><b>Start</b>: the first counted crop break. The caller only reports breaks that happened on
 *       a farming island with a farming tool in hand.</li>
 *   <li><b>Active time</b>: the gaps between consecutive breaks, each counted only when it is at most
 *       the pause threshold. A longer gap is a pause - the AFK minutes, the walk to the Composter -
 *       and adds nothing. Time after the last break never counts.</li>
 *   <li><b>End</b>: leaving the farming islands, no break for the end threshold, or a manual end.
 *       An idle end is dated at the last break, so the trailing idle is not part of the session.</li>
 * </ul>
 */
public final class FarmingSessionClock {

    /** Why a session ended. */
    public enum EndReason {
        LEFT_ISLAND("left the farming islands"),
        IDLE("idle"),
        MANUAL("ended by hand");

        private final String text;

        EndReason(String text) {
            this.text = text;
        }

        public String text() {
            return text;
        }
    }

    /** A finished session's times. */
    public record Ended(long startedAt, long endedAt, long activeMs, EndReason reason) {
    }

    private final long pauseMs;
    private final long endMs;

    private boolean running;
    private long startedAt;
    private long lastBreakAt;
    private long activeMs;

    /**
     * @param pauseMs a gap between breaks longer than this is a pause, not farming
     * @param endMs   no break for this long ends the session
     */
    public FarmingSessionClock(long pauseMs, long endMs) {
        this.pauseMs = Math.max(1, pauseMs);
        this.endMs = Math.max(this.pauseMs, endMs);
    }

    public boolean running() {
        return running;
    }

    public long startedAt() {
        return startedAt;
    }

    public long activeMs() {
        return activeMs;
    }

    /**
     * A counted crop break.
     *
     * @return true when this break started a new session
     */
    public boolean onCropBroken(long now) {
        if (!running()) {
            running = true;
            startedAt = now;
            lastBreakAt = now;
            activeMs = 0;
            return true;
        }
        long gap = now - lastBreakAt;
        if (gap > 0 && gap <= pauseMs) {
            activeMs += gap;
        }
        lastBreakAt = Math.max(lastBreakAt, now);
        return false;
    }

    /**
     * Every tick: ends the session when the player has left the farming islands or has been idle
     * for the end threshold.
     *
     * @return the finished session, or null when nothing ended
     */
    public Ended tick(long now, boolean onFarmingIsland) {
        if (!running()) {
            return null;
        }
        if (!onFarmingIsland) {
            return finish(lastBreakAt, EndReason.LEFT_ISLAND);
        }
        if (now - lastBreakAt >= endMs) {
            return finish(lastBreakAt, EndReason.IDLE);
        }
        return null;
    }

    /** Ends the running session now, or returns null when none is running. */
    public Ended endManually() {
        return running() ? finish(lastBreakAt, EndReason.MANUAL) : null;
    }

    private Ended finish(long endedAt, EndReason reason) {
        Ended ended = new Ended(startedAt, endedAt, activeMs, reason);
        running = false;
        startedAt = 0;
        lastBreakAt = 0;
        activeMs = 0;
        return ended;
    }
}
