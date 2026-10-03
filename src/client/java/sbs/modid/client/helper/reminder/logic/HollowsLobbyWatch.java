/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.reminder.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.util.PlainText;
import sbs.modid.client.helper.timers.ServerWorldTime;

import java.lang.ref.WeakReference;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The live state of the Crystal Hollows lobby you are in: its clock, how fast that clock really runs,
 * which lobby it is - and the {@code [SBS][LobbyDay]} log that is meant to confirm the closing day.
 *
 * <p>Once a second on the Hollows it samples {@link ServerWorldTime} into a
 * {@link HollowsLobbyClock.Rate}, works out the card's text, and settles the lobby's id. Everything
 * the reminder, the card and the leave guard need is read from here, so all three agree on one
 * number.
 *
 * <p><b>The capture.</b> Day 19 is maintainer-reported, and the threshold has moved before. This
 * class writes down what would confirm it from ordinary play, and nothing more - no setting is ever
 * changed from it:
 * <ul>
 *   <li>one line per lobby joined: id, F3 day with fraction, the raw clock, its rate, game time,
 *       wall clock;</li>
 *   <li>on coming back to the Hollows within {@link #REJOIN_WINDOW_MS}: the lobby left, its day when
 *       left, and the lobby landed in. The same id means getting back in worked at that day; a
 *       different one means it did not, or that the player went elsewhere on purpose - the log
 *       cannot tell, so it records rather than concludes;</li>
 *   <li>warp-related chat lines ({@link #WARP_LINE}) while on, or just off, the Hollows, with the day.</li>
 * </ul>
 *
 * <p>A lobby is a world: every Hollows instance is its own server, so the state is per {@code Level},
 * and the id is that server's name. Main thread only (tick and chat hooks).
 */
public final class HollowsLobbyWatch {

    private static final HollowsLobbyWatch INSTANCE = new HollowsLobbyWatch();

    private static final long TICK_MS = 1_000L;
    /** A return to the Hollows within this long of leaving is logged as a rejoin attempt. */
    static final long REJOIN_WINDOW_MS = 30L * 60_000L;
    /** How long to wait for the tab list to name the server before standing in the world's identity. */
    private static final long NAME_GRACE_MS = 10_000L;
    /** Chat after leaving is still about the lobby left for this long - the warp's own replies. */
    private static final long CHAT_AFTER_LEAVE_MS = 60_000L;

    /**
     * Lines worth recording beside the lobby day. Broad on purpose and only ever logged: no line in
     * the instance logs this was written against says a lobby is closed, so this casts wide and the
     * capture narrows it. What those logs do contain (2026-10-03):
     * <ul>
     *   <li>"Couldn't warp you!" alone, and "Couldn't warp you! Try again later. (REASON)" with
     *       PLAYER_TRANSFER_COOLDOWN, SERVERS_DID_NOT_ACCEPT, NO_DESTINATION_FOUND,
     *       DYNAMIC_POOL_ERROR or WRONG_TOKEN - the second and third are the likeliest shape of a
     *       refused rejoin;</li>
     *   <li>"Sending to server mini157CR..." - names the destination, so a rejoin can be read off it;</li>
     *   <li>"This server is full!", "Warping using transfer token...", "Evacuating to Hub...",
     *       "You are being transferred to the HUB for being AFK!".</li>
     * </ul>
     * The bare "Warping..." progress line is left out; it says nothing about the lobby.
     */
    static final Pattern WARP_LINE = Pattern.compile(
            "(?i)(couldn'?t warp|could not warp|can'?t warp|cannot warp|unable to warp|sending you to"
                    + "|sending to server|warping you to|evacuating|transfer|no longer accepting"
                    + "|is closed|closed to|lobby is full|server is full|not allowed to warp)");

    private static final DateTimeFormatter WALL = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    /** One world's worth of state. */
    private static final class Visit {
        final WeakReference<Level> level;
        final long since;
        String key;
        boolean hollows;
        boolean joinLogged;
        boolean negativeLogged;
        long lastTicks = -1L;

        Visit(Level level, long since) {
            this.level = new WeakReference<>(level);
            this.since = since;
        }
    }

    /** The Hollows lobby last left, for the rejoin check. */
    private record Left(String key, long ticks, long at) {
    }

    private final HollowsLobbyClock.Rate rate = new HollowsLobbyClock.Rate();
    private Visit visit;
    private Left left;
    private long lastTickAt;
    private long lastReceivedAt;

    /** The session's rejoin evidence, in clock ticks; -1 = none yet. */
    private long rejoinWorkedUpTo = -1L;
    private long elsewhereFrom = -1L;

    private volatile boolean inHollows;
    private volatile long clockTicks = -1L;
    private volatile double ticksPerSecond = HollowsLobbyClock.NOMINAL_TICKS_PER_SECOND;
    private volatile String cardText = "";
    private volatile double progress = Double.NEGATIVE_INFINITY;

    private HollowsLobbyWatch() {
    }

    public static HollowsLobbyWatch getInstance() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------ what the others read

    /** Whether the last sample found the player on the Crystal Hollows. */
    public boolean inHollows() {
        return inHollows;
    }

    /** The lobby clock at the last sample, below zero while unknown or off the Hollows. */
    public long clockTicks() {
        return clockTicks;
    }

    /** Clock ticks per real second: measured over the last minute, else the nominal rate. */
    public double ticksPerSecond() {
        return ticksPerSecond;
    }

    /** The lobby's id (its server name), or {@code null} until it is known. */
    public String lobbyKey() {
        Visit v = visit;
        return v == null ? null : v.key;
    }

    /** The card's line, worked out once a second. Empty off the Hollows. */
    public String cardText() {
        return cardText;
    }

    /** {@link HollowsLobbyReminder#progress} at the last sample; -infinity while unknown. */
    public double progress() {
        return progress;
    }

    // ------------------------------------------------------------------ hooks

    /** Every client tick; real work once a second, and only a location read off the Hollows. */
    public void onClientTick() {
        long now = System.currentTimeMillis();
        if (now - lastTickAt < TICK_MS) {
            return;
        }
        lastTickAt = now;
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        if (visit == null || visit.level.get() != level) {
            closeVisit(now);
            visit = new Visit(level, now);
        }
        boolean here = SkyBlockLocation.onIsland(HollowsLobbyReminder.ISLAND);
        inHollows = here;
        if (!here) {
            clockTicks = -1L;
            cardText = "";
            return;
        }
        visit.hollows = true;
        sample(now);
        if (visit.key == null) {
            String name = ServerWorldTime.serverName();
            if (name != null) {
                visit.key = name;
            } else if (now - visit.since >= NAME_GRACE_MS) {
                visit.key = "world@" + Integer.toHexString(System.identityHashCode(level));
            }
        }
        if (!visit.joinLogged && visit.key != null
                && (clockTicks >= 0L || now - visit.since >= NAME_GRACE_MS)) {
            visit.joinLogged = true;
            logJoin(now);
        }
    }

    /** A world change: the lobby being left is written down for the rejoin check. */
    public void onWorldChange() {
        closeVisit(System.currentTimeMillis());
        visit = null;
        inHollows = false;
        clockTicks = -1L;
        cardText = "";
    }

    /** Every chat line: warp-related ones are logged with the lobby day while it is relevant. */
    public void onChat(String text) {
        Left last = left;
        boolean recent = last != null && System.currentTimeMillis() - last.at() <= CHAT_AFTER_LEAVE_MS;
        if (!inHollows && !recent) {
            return;
        }
        String line = PlainText.strip(text).strip();
        if (line.isEmpty() || !WARP_LINE.matcher(line).find()) {
            return;
        }
        String key = lobbyKey();
        String day = inHollows
                ? describeDay(clockTicks) + " in " + (key == null ? "?" : key)
                : describeDay(last.ticks()) + " in " + last.key() + " (left)";
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][LobbyDay] chat at {}: \"{}\"", day, line);
    }

    // ------------------------------------------------------------------ internals

    private void sample(long now) {
        long ticks = ServerWorldTime.overworldClockTicks();
        long received = ServerWorldTime.receivedAtMs();
        long gameTime = ServerWorldTime.gameTime();
        if (ticks >= 0L && gameTime >= 0L && received != lastReceivedAt) {
            lastReceivedAt = received;
            rate.add(received, ticks, gameTime);
        }
        if (ticks < -1L && !visit.negativeLogged) {
            // Not something a 26.2 server sends; logged once so a translator doing it is noticed.
            visit.negativeLogged = true;
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][LobbyDay] negative overworld clock {} - treated as unknown",
                    ticks);
        }
        clockTicks = ticks;
        visit.lastTicks = ticks;
        ticksPerSecond = HollowsLobbyClock.effectiveTicksPerSecond(rate.clockTicksPerSecond(),
                ServerWorldTime.overworldClockRate());
        SBSConfig.RemindersSettings cfg = ConfigManager.getInstance().get().reminders;
        progress = ticks < 0L ? Double.NEGATIVE_INFINITY
                : HollowsLobbyReminder.progress(ticks, cfg.hollowsCloseDay, cfg.hollowsWarnMinutes);
        cardText = cardText(ticks, ticksPerSecond, cfg.hollowsCloseDay);
    }

    /** {@code "Day 17.4 · closes in ~32 min"}, and its variants for a stopped, closed or unknown clock. */
    static String cardText(long ticks, double ticksPerSecond, int closeDay) {
        if (ticks < 0L) {
            return "Day ? · clock not sent";
        }
        String day = "Day " + HollowsLobbyClock.dayText(ticks);
        if (ticks >= HollowsLobbyClock.closeAtTicks(closeDay)) {
            return day + " · closed to warps";
        }
        long seconds = HollowsLobbyClock.secondsUntil(ticks, closeDay, ticksPerSecond);
        return seconds < 0L ? day + " · clock stopped"
                : day + " · closes in " + HollowsLobbyClock.etaText(seconds);
    }

    private void logJoin(long now) {
        Visit v = visit;
        long ticks = clockTicks;
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][LobbyDay] join lobby={} day={} (F3 {}) clockTicks={} rate={} clockPackets={} "
                        + "gameTime={} at {}",
                v.key, ticks < 0L ? "?" : HollowsLobbyClock.dayText(ticks),
                ticks < 0L ? "?" : ServerWorldTime.f3Day(ticks), ticks,
                ServerWorldTime.overworldClockRate(), ServerWorldTime.overworldClockPackets(),
                ServerWorldTime.gameTime(), LocalTime.now().format(WALL));
        Left last = left;
        left = null;
        if (last == null || now - last.at() > REJOIN_WINDOW_MS) {
            return;
        }
        boolean same = last.key().equals(v.key);
        long away = (now - last.at()) / 1000L;
        if (same) {
            rejoinWorkedUpTo = Math.max(rejoinWorkedUpTo, last.ticks());
        } else if (last.ticks() >= 0L && last.ticks() >= rejoinWorkedUpTo
                && (elsewhereFrom < 0L || last.ticks() < elsewhereFrom)) {
            elsewhereFrom = last.ticks();
        }
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][LobbyDay] rejoin check after {}s: left {} at {}, now in {} - {}",
                away, last.key(), describeDay(last.ticks()), v.key,
                same ? "got back in" : "a different lobby (refused, or chosen)");
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][LobbyDay] this session: back into the same lobby up to {}; a different lobby "
                        + "from {}", describeDay(rejoinWorkedUpTo), describeDay(elsewhereFrom));
    }

    /** Remembers the Hollows lobby being left; nothing for a world that never was the Hollows. */
    private void closeVisit(long now) {
        Visit v = visit;
        rate.clear();
        lastReceivedAt = 0L;
        if (v == null || !v.hollows || v.key == null) {
            return;
        }
        left = new Left(v.key, v.lastTicks, now);
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][LobbyDay] left lobby {} at {}", v.key,
                describeDay(v.lastTicks));
    }

    private static String describeDay(long ticks) {
        return ticks < 0L ? "day ?" : "day " + HollowsLobbyClock.dayText(ticks);
    }
}
