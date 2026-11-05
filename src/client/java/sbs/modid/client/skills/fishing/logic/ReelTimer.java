/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.fishing.logic;

import java.util.ArrayDeque;

/**
 * How long the bobber waits for a bite: the live wait of the current cast, the session mean, and a
 * rolling mean over the last few casts.
 *
 * <p><b>Signals.</b> The wait starts when the bobber lands ({@link #onLanded}: the first tick the
 * hook reports water or lava contact), or at the bobber's spawn ({@link #onCast}) for as long as no
 * contact has been seen. It ends on the bite: Hypixel's "{@code !!!}" armor stand over the bobber,
 * which {@link BiteIndicator} already detects for the bite alert - the same edge that counts
 * "Times Fished". The sound is not used: it is not specific to the player's own bobber.
 *
 * <p><b>What counts.</b> A wait becomes a sample only when the bite is <b>reeled</b>: the bobber
 * leaves the world within {@link #REEL_GRACE_MS} of the bite. A recast with no bite at all, and a
 * bite that was let go, are not samples - the first measures the player, the second would record a
 * wait the player did not act on. A bite that was let go restarts the wait from the moment the
 * marker went away, so the next bite on the same cast measures only its own wait.
 *
 * <p><b>Without a bite marker.</b> Until the first bite marker of the game session has been seen,
 * the reel stands in for the bite: a cast reeled after at least {@link #FALLBACK_MIN_MS} becomes a
 * sample timed from the wait's start to the reel. That keeps the section useful where the marker is
 * never detected, at the cost of counting a recast without a bite as a (long) sample - which is why
 * it stops for good the moment a real marker is seen.
 *
 * <p>Kept free of Minecraft (the clock is passed in) so the rules are tested, not fished for.
 */
public final class ReelTimer {

    /** How long after the bite a vanishing bobber still counts as reeling that bite. */
    static final long REEL_GRACE_MS = 2_500L;

    /** Largest rolling window the settings allow; the deque never grows past it. */
    static final int MAX_ROLLING = 100;

    /** Shortest cast the marker-less fallback records: a quicker reel is a mis-cast, not a wait. */
    static final long FALLBACK_MIN_MS = 1_000L;

    /** When the current wait started, or -1 with no bobber out. */
    private long waitStart = -1;
    /** Whether the current cast has reported fluid contact yet. */
    private boolean landed;
    /** When the current bite started, or -1 while waiting. */
    private long biteAt = -1;
    /** When the current bite's marker went away, or -1. */
    private long biteEndAt = -1;

    private int samples;
    private long sumMs;
    /** Newest last; trimmed to the configured N whenever a sample is added. */
    private final ArrayDeque<Integer> recent = new ArrayDeque<>();
    private int missedBites;
    private int castsWithoutBite;
    /** Bumped whenever a statistic changes - the HUD's rebuild key. */
    private int revision;
    /** Whether any bite marker has been seen; until then a reel stands in for the bite. Never reset. */
    private boolean biteMarkerSeen;

    /** The bobber appeared. */
    public void onCast(long now) {
        waitStart = now;
        landed = false;
        biteAt = -1;
        biteEndAt = -1;
    }

    /** The bobber touched water or lava for the first time this cast: the wait starts here. */
    public boolean onLanded(long now) {
        if (waitStart < 0 || landed) {
            return false;
        }
        landed = true;
        if (biteAt < 0) {
            waitStart = now;
        }
        return true;
    }

    /** The bite marker appeared over the bobber. */
    public void onBite(long now) {
        biteMarkerSeen = true;
        if (waitStart < 0) {
            return;
        }
        if (biteAt >= 0) {
            // A previous bite on this cast was let go: this bite's wait began when that one ended.
            missedBites++;
            revision++;
            waitStart = biteEndAt >= 0 ? biteEndAt : biteAt;
        }
        biteAt = now;
        biteEndAt = -1;
    }

    /** The bite marker went away while the bobber stayed out. */
    public void onBiteEnd(long now) {
        if (biteAt >= 0) {
            biteEndAt = now;
        }
    }

    /**
     * The bobber left the world. A sample when it was reeled on a bite; otherwise the cast is
     * dropped.
     *
     * @param rolling the configured N of the rolling mean
     * @return the recorded wait in ms, or -1 when this cast produced no sample
     */
    public long onReel(long now, int rolling) {
        if (waitStart < 0) {
            return -1;
        }
        long sample = -1;
        if (biteAt >= 0 && now - biteAt <= REEL_GRACE_MS) {
            sample = Math.max(0, biteAt - waitStart);
        } else if (biteAt < 0 && !biteMarkerSeen && now - waitStart >= FALLBACK_MIN_MS) {
            sample = now - waitStart;   // no marker ever seen: the reel is the best bite signal there is
        }
        if (sample >= 0) {
            samples++;
            sumMs += sample;
            recent.addLast((int) Math.min(Integer.MAX_VALUE, sample));
            trim(rolling);
        } else if (biteAt < 0) {
            castsWithoutBite++;
        } else {
            missedBites++;
        }
        revision++;
        waitStart = -1;
        biteAt = -1;
        biteEndAt = -1;
        return sample;
    }

    private void trim(int rolling) {
        int n = Math.max(1, Math.min(MAX_ROLLING, rolling));
        while (recent.size() > n) {
            recent.removeFirst();
        }
    }

    /** Whether the cast landed in fluid (false: the wait is timed from the bobber's spawn). */
    public boolean landed() {
        return landed;
    }

    /**
     * The current cast's wait in ms: live while waiting, frozen at the bite while biting, -1 with no
     * bobber out.
     */
    public long currentWait(long now) {
        if (waitStart < 0) {
            return -1;
        }
        boolean biting = biteAt >= 0 && biteEndAt < 0;
        return Math.max(0, (biting ? biteAt : now) - waitStart);
    }

    /** Session mean in ms, -1 before the first sample. */
    public long meanMs() {
        return samples == 0 ? -1 : sumMs / samples;
    }

    /** Mean of the last {@code rolling} samples in ms, -1 before the first sample. */
    public long rollingMeanMs(int rolling) {
        int n = Math.max(1, Math.min(MAX_ROLLING, rolling));
        if (recent.isEmpty()) {
            return -1;
        }
        long sum = 0;
        int count = 0;
        var it = recent.descendingIterator();
        while (it.hasNext() && count < n) {
            sum += it.next();
            count++;
        }
        return sum / count;
    }

    public int samples() {
        return samples;
    }

    public int missedBites() {
        return missedBites;
    }

    public int castsWithoutBite() {
        return castsWithoutBite;
    }

    public int revision() {
        return revision;
    }

    /** Whether a bite marker has been seen, i.e. samples are bite-timed rather than reel-timed. */
    public boolean biteMarkerSeen() {
        return biteMarkerSeen;
    }

    /** Clears the session's statistics; a cast in progress keeps running. */
    public void reset() {
        samples = 0;
        sumMs = 0;
        recent.clear();
        missedBites = 0;
        castsWithoutBite = 0;
        revision++;
    }
}
