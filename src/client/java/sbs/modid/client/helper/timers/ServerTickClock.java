/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.timers;

/**
 * The server's tick counter, readable at any instant: the game time of the last
 * {@code ClientboundSetTimePacket}, advanced by the wall time since that packet arrived.
 *
 * <p><b>Why the time packet.</b> It is the only thing the server sends that carries its own tick
 * number. A wall clock counts real time, and a server running at 15 TPS does a fifth fewer ticks in
 * a second than a wall clock assumes, so anything that fires on a tick cycle drifts away from a
 * wall-clock countdown within a few periods. Counting server ticks keeps a tick-cycle prediction
 * correct at any tick rate; only the conversion to seconds for display depends on the rate.
 * Ping and pong packets were considered and rejected: they carry no tick number, only a round trip.
 *
 * <p><b>Interpolation, and its one limit.</b> A vanilla server sends the packet every 20 ticks.
 * Between packets the clock assumes 20 ticks per second, but never runs more than
 * {@link #MAX_LEAD_TICKS} ahead of the last packet: a lagging server delivers its next packet late,
 * and without the cap the clock would count ticks the server never did. With the cap, a slow server
 * makes the clock pause at the next expected packet instead of running ahead of it. The next packet
 * then re-anchors it, so the error never accumulates past one sync interval.
 *
 * <p>Reads are monotonic: a packet that arrives early (two delivered in one network batch) moves the
 * clock forward, never back, unless the game time itself went backwards, which is a world change.
 *
 * <p>Fed by {@link ServerWorldTime#onTimeUpdate} on the main thread; read on the main thread. The
 * instance methods take the time as an argument, so the arithmetic is tested without a game.
 */
public final class ServerTickClock {

    /** Nominal server tick length. */
    static final double NANOS_PER_TICK = 50_000_000.0;

    /** How far past the last packet the clock may run: one vanilla sync interval. */
    static final double MAX_LEAD_TICKS = 20.0;

    private static final ServerTickClock INSTANCE = new ServerTickClock();

    private long anchorGameTime = -1L;
    private long anchorNanos;
    private double lastRead = -1.0;

    /** A detached clock for tests; the game uses {@link #getInstance()}. */
    public ServerTickClock() {
    }

    public static ServerTickClock getInstance() {
        return INSTANCE;
    }

    /** The server tick now, or {@code -1} before the first packet of this world. */
    public double now() {
        return ticksAt(System.nanoTime());
    }

    // ---- the clock itself, on explicit time ----------------------------------------------------

    /** A time packet carrying {@code gameTime} arrived at {@code nowNanos}. */
    public void onGameTime(long gameTime, long nowNanos) {
        if (anchorGameTime >= 0L && gameTime < anchorGameTime) {
            reset(); // game time went backwards: a different world, never a late packet
        }
        anchorGameTime = gameTime;
        anchorNanos = nowNanos;
    }

    /** The server tick at {@code nowNanos}, or {@code -1} while no packet has arrived. */
    public double ticksAt(long nowNanos) {
        if (anchorGameTime < 0L) {
            return -1.0;
        }
        double lead = Math.max(0.0, (nowNanos - anchorNanos) / NANOS_PER_TICK);
        double value = anchorGameTime + Math.min(lead, MAX_LEAD_TICKS);
        if (value < lastRead) {
            return lastRead; // an early packet re-anchored below a value already handed out
        }
        lastRead = value;
        return value;
    }

    /** Forgets the world: the next packet starts the clock afresh. */
    void reset() {
        anchorGameTime = -1L;
        anchorNanos = 0L;
        lastRead = -1.0;
    }

    // ---- fed by ServerWorldTime ----------------------------------------------------------------

    static void packet(long gameTime) {
        INSTANCE.onGameTime(gameTime, System.nanoTime());
    }

    static void worldChanged() {
        INSTANCE.reset();
    }
}
