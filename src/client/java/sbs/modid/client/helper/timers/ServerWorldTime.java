/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.timers;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.world.clock.ClockNetworkState;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.level.Level;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.tab.TabWidgets;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The two clocks the server actually sends, taken straight off {@code ClientboundSetTimePacket}
 * before anything on the client can reinterpret them. Fed by {@code TimeUpdateMixin}.
 *
 * <p><b>Why not just read the level.</b> Both of the level's own accessors lie for this purpose:
 *
 * <ul>
 *   <li>{@code Level.getOverworldClockTime()} goes through
 *       {@code ClientClockManager.getTotalTicks}, which our own Dark Mode client-time setting
 *       overrides for the whole client - with it on, the clock reports the hour the player picked
 *       and any day count off it is pinned at zero.</li>
 *   <li>That same manager builds its clock lazily: {@code getInstance} is a
 *       {@code computeIfAbsent} whose fresh instance starts at <b>zero ticks and rate 1</b>. A
 *       server that never sends an update for {@code minecraft:overworld} therefore leaves a clock
 *       that free-runs from the moment <i>you</i> joined - a session timer wearing the world's
 *       clothes, and indistinguishable from a real reading after the fact.</li>
 * </ul>
 *
 * <p>Reading the packet keeps those apart: a value that was never sent stays {@code -1} and callers
 * can say "unknown" instead of publishing a number the server never gave them.
 *
 * <p>One line per world is logged under {@code [SBS][Timers]} with the instance name and both
 * counters. Reading it is what settled which of the two carries a lobby's age - see
 * {@code EventTimers.lobbyDay} - and it stays because a run of those lines is the only cheap way to
 * check that again: subtract each reading from its own timestamp and you get the server's boot time.
 */
public final class ServerWorldTime {

    /** Ticks in one Minecraft day - the division behind every "Day N" readout. */
    public static final long DAY_TICKS = 24_000L;

    /**
     * The world the readings belong to, weakly held so a disconnected level is still collectable.
     * A reading from the previous lobby must not survive a warp even for the one second it would
     * take the next packet to arrive.
     */
    private static volatile WeakReference<Level> world = new WeakReference<>(null);

    /*
     * The overworld clock as last sent, and the game time of the packet that carried it. A vanilla
     * 26.2 server sends the clock on join and on a change only - its once-a-second sync carries the
     * game time alone - and the client advances its copy by rate x game-time delta in between. The
     * reading is therefore these four values, not the first one alone; see overworldClockTicks().
     */
    private static volatile long clockTicks = -1L;
    private static volatile float clockPartial;
    private static volatile float clockRate = 1.0F;
    private static volatile long clockGameTime = -1L;
    private static volatile long gameTime = -1L;
    /** Wall-clock time the last packet of any kind arrived, for a measured rate. */
    private static volatile long receivedAtMs;
    /** Packets in this world that carried the overworld clock - logged, it is the open question. */
    private static volatile int clockPackets;
    private static boolean logged;
    private static int packetsHere;

    /** "Server: mini24CD" in the tab widget - the only name Hypixel gives an instance. */
    private static final Pattern SERVER = Pattern.compile("(?i)^server:\\s*(\\S+)");

    /**
     * How many packets to wait for the tab widget before logging anyway. The name is what makes a
     * sample worth anything - two readings are only comparable if you know whether they came from
     * the same instance - but one arrives a second after the world does, so the log waits for it
     * rather than recording an anonymous number.
     */
    private static final int NAME_GRACE_PACKETS = 20;

    private ServerWorldTime() {
    }

    /** Called from {@code TimeUpdateMixin} for every time-update packet (main thread). */
    public static void onTimeUpdate(ClientboundSetTimePacket packet) {
        Level current = Minecraft.getInstance().level;
        if (current != world.get()) {
            world = new WeakReference<>(current);
            clockTicks = -1L;
            clockPartial = 0.0F;
            clockRate = 1.0F;
            clockGameTime = -1L;
            clockPackets = 0;
            logged = false;
            packetsHere = 0;
        }
        packetsHere++;
        gameTime = packet.gameTime();
        receivedAtMs = System.currentTimeMillis();
        for (Map.Entry<Holder<WorldClock>, ClockNetworkState> update : packet.clockUpdates().entrySet()) {
            if (update.getKey().is(WorldClocks.OVERWORLD)) {
                ClockNetworkState state = update.getValue();
                clockTicks = state.totalTicks();
                clockPartial = state.partialTick();
                clockRate = state.rate();
                clockGameTime = gameTime;
                clockPackets++;
            }
        }
        if (!logged) {
            String server = serverName();
            if (server != null || packetsHere >= NAME_GRACE_PACKETS) {
                logged = true;
                SkyblockSimplifiedSBS.LOGGER.info(
                        "[SBS][Timers] server={} world time: gameTime={} (day {}), overworld clock {}",
                        server == null ? "?" : server, gameTime, gameTime / DAY_TICKS,
                        clockGameTime < 0L ? "not sent"
                                : clockTicks + " (day " + f3Day(clockTicks) + ", rate " + clockRate + ")");
            }
        }
    }

    /**
     * The instance name Hypixel publishes in the tab widget ("mini24CD"), or {@code null} before it
     * shows up.
     *
     * <p>Public because it is the only place the client is ever told which instance it is on, and
     * two features need that: this one, to say whether two clock readings are even comparable, and
     * Streamer Mode, to know the exact string it has to take back out of everything on screen.
     */
    public static String serverName() {
        for (String line : TabWidgets.lines()) {
            String name = serverNameOf(line);
            if (name != null) {
                return name;
            }
        }
        return null;
    }

    /** The instance name one tab line carries ({@code "Server: mini24CD"}), or {@code null}. Pure. */
    public static String serverNameOf(String line) {
        if (line == null) {
            return null;
        }
        Matcher m = SERVER.matcher(line.trim());
        return m.find() ? m.group(1) : null;
    }

    /**
     * The overworld day-night clock in ticks now - the value F3's "Day #" is computed from - or
     * {@code -1} if this server has never reported one.
     *
     * <p>Not simply the last value sent. The client's {@code ClientClockManager} advances its clock by
     * {@code rate x game-time delta} between clock updates, and a vanilla 26.2 server sends the clock
     * only on join and on a change, so the raw value would freeze at the join reading while F3 counted
     * on. This applies the same advance against the latest game time from the server; it trails the
     * client's own copy by at most the client ticks since that packet (about a second).
     *
     * <p>A negative total - which no vanilla 26.2 server sends, the server clamps at zero - is
     * returned as it is; callers treat anything below zero as unknown.
     */
    public static long overworldClockTicks() {
        if (Minecraft.getInstance().level != world.get() || clockGameTime < 0L) {
            return -1L;
        }
        return extrapolate(clockTicks, clockPartial, clockRate, clockGameTime, gameTime);
    }

    /**
     * {@code ClientClockManager.tick}'s advance in one step: the clock {@code ticks} (with
     * {@code partial} carried) sent at game time {@code from}, moved on to game time {@code to} at
     * {@code rate} clock ticks per game tick. A rate of 0 - a stopped day-night cycle - returns
     * {@code ticks} unchanged. Game time running backwards (a packet out of order) moves nothing.
     */
    public static long extrapolate(long ticks, float partial, float rate, long from, long to) {
        long delta = to - from;
        if (delta <= 0L || rate == 0.0F) {
            return ticks;
        }
        return ticks + (long) Math.floor(partial + (double) delta * rate);
    }

    /**
     * F3's "Day #N" for a clock total: {@code DebugEntryDayCount} prints
     * {@code Timeline.getPeriodCount}, which is {@code (int) (totalTicks / 24000)} for the overworld
     * day timeline. Java's truncation toward zero is kept, so a negative total gives what F3 would.
     */
    public static int f3Day(long totalTicks) {
        return (int) (totalTicks / DAY_TICKS);
    }

    /**
     * The overworld clock's rate as last sent: clock ticks per game tick, {@code 1} normally,
     * {@code 0} when the server has stopped the day-night cycle. {@code NaN} before any clock arrived.
     */
    public static float overworldClockRate() {
        return Minecraft.getInstance().level == world.get() && clockGameTime >= 0L ? clockRate : Float.NaN;
    }

    /** How many packets in this world carried the overworld clock (0 before the first). */
    public static int overworldClockPackets() {
        return Minecraft.getInstance().level == world.get() ? clockPackets : 0;
    }

    /** Wall-clock milliseconds the last time packet arrived in this world, or {@code 0}. */
    public static long receivedAtMs() {
        return Minecraft.getInstance().level == world.get() ? receivedAtMs : 0L;
    }

    /**
     * The world's game time in ticks as the server last reported it, or {@code -1} before the first
     * packet. Note that this is the age of the <i>world</i>, which on Hypixel is a long-lived save
     * shared by every instance of an island - it is not how long the lobby has been up.
     */
    public static long gameTime() {
        return Minecraft.getInstance().level == world.get() ? gameTime : -1L;
    }
}
