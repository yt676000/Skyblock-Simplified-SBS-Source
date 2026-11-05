/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.fishing.logic;

/**
 * The cast-driven half of the fishing session: "Times Fished", the session clock, "Per Hour" and the
 * Reel Timer, all fed by one per-tick question - is the player's bobber out?
 *
 * <p><b>Why casts, not bites.</b> These counters used to hang off the bite marker (Hypixel's "!!!"
 * stand over the bobber) and off a tick body that ran several other readers first. When the marker
 * was not seen, or any reader ahead of it failed, every one of them stayed at 0 while chat-booked
 * catches kept counting. The bobber appearing ({@code player.fishing} going non-null) is the one
 * signal the client always has, so each counter here works from it alone; the bite only refines the
 * Reel Timer.
 *
 * <ul>
 *   <li><b>Times Fished</b>: one per cast (the bobber's false-to-true edge).</li>
 *   <li><b>Time</b>: {@link FishingSessionClock}, with cast, reel, bite and catch as events.</li>
 *   <li><b>Per Hour</b>: the session profit over that time, once it is long enough to mean anything.</li>
 *   <li><b>Reel Timer</b>: {@link ReelTimer}, cast (or landing) to bite, reeled.</li>
 * </ul>
 *
 * <p>Kept free of Minecraft (the bobber state and clock are passed in) so it is tested with fake ticks.
 */
public final class CastSession {

    /** What a tick's bobber state meant. */
    public enum Edge { NONE, CAST, REEL }

    /** Below this much fished time Per Hour stays 0: one early drop would extrapolate into billions. */
    static final long MIN_PER_HOUR_MS = 5_000L;

    private final ReelTimer reelTimer = new ReelTimer();
    private final FishingSessionClock clock = new FishingSessionClock();
    private boolean bobberOut;
    private int timesFished;
    /** Bumped when Times Fished changes - part of the HUD's rebuild key via the tracker revision. */
    private int revision;
    /** The Reel Timer sample the latest reel produced, or -1. */
    private long lastReelSample = -1;
    /** Whether this tick was the current cast's first fluid contact. */
    private boolean landedThisTick;

    /**
     * One tick's look at the bobber.
     *
     * @param bobber  whether the player's bobber is in the world
     * @param inFluid whether it reports water or lava contact this tick
     * @param pauseMs the session clock's pause length
     * @param rolling the Reel Timer's rolling-mean length
     * @return the edge this tick produced
     */
    public Edge onTick(boolean bobber, boolean inFluid, long now, long pauseMs, int rolling) {
        Edge edge = Edge.NONE;
        if (bobber && !bobberOut) {
            timesFished++;
            revision++;
            clock.onEvent(now, pauseMs);
            reelTimer.onCast(now);
            edge = Edge.CAST;
        } else if (!bobber && bobberOut) {
            clock.onEvent(now, pauseMs);
            lastReelSample = reelTimer.onReel(now, rolling);
            edge = Edge.REEL;
        }
        if (bobber && inFluid) {
            landedThisTick = reelTimer.onLanded(now);
        } else {
            landedThisTick = false;
        }
        bobberOut = bobber;
        return edge;
    }

    /** The bite marker appeared over the bobber. */
    public void onBite(long now, long pauseMs) {
        clock.onEvent(now, pauseMs);
        reelTimer.onBite(now);
    }

    /** The bite marker went away with the bobber still out. */
    public void onBiteEnd(long now) {
        reelTimer.onBiteEnd(now);
    }

    /** Any other proof of fishing (a catch line, a loot share): keeps the clock running. */
    public void onActivity(long now, long pauseMs) {
        clock.onEvent(now, pauseMs);
    }

    public int timesFished() {
        return timesFished;
    }

    public boolean bobberOut() {
        return bobberOut;
    }

    /** Fished time in milliseconds. */
    public long sessionMillis(long now, long pauseMs) {
        return clock.millis(now, pauseMs);
    }

    /** {@code profit} extrapolated to an hour of fished time; 0 until {@link #MIN_PER_HOUR_MS}. */
    public double perHour(double profit, long now, long pauseMs) {
        long millis = sessionMillis(now, pauseMs);
        if (millis < MIN_PER_HOUR_MS) {
            return 0;
        }
        return profit / (millis / 3_600_000.0);
    }

    public ReelTimer reelTimer() {
        return reelTimer;
    }

    public long lastReelSample() {
        return lastReelSample;
    }

    public boolean landedThisTick() {
        return landedThisTick;
    }

    public int revision() {
        return revision;
    }

    /** Clears the counters; a cast in progress keeps its bobber state so its reel is still seen. */
    public void reset() {
        timesFished = 0;
        revision++;
        clock.reset();
        reelTimer.reset();
        lastReelSample = -1;
    }
}
