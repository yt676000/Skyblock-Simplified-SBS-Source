/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.reminder.logic;

/**
 * When a Crystal Hollows lobby stops accepting warps, and the once-per-lobby state of saying so.
 *
 * <p>From a given lobby day (19 by default, maintainer-reported) nobody can warp into a Crystal
 * Hollows instance any more - a party member left outside stays outside, and so do you once you
 * leave. The day is the server's day-night clock as {@code ServerWorldTime} reads it, the number F3
 * shows as "Day #N".
 *
 * <p>Three notices per lobby, each at most once:
 * <ul>
 *   <li>{@link Kind#JOIN} - the first reading in a lobby that is already inside the lead window;</li>
 *   <li>{@link Kind#LEAD} - the lead window reached while you are in the lobby;</li>
 *   <li>{@link Kind#CLOSED} - the cutoff day reached. A lobby first seen past it gets only this.</li>
 * </ul>
 *
 * <p>The lead window is a clock position, not a real-time interval: {@code warnMinutes} minutes of
 * clock, 20 to a day, so the default 20 warns from day 18.0 whatever the lobby's lag. The ETA the
 * notices quote is real time, from the measured rate.
 *
 * <p>A lobby is its server name. Time spent off the Hollows does not count as a lobby change, so
 * stepping out to the Hub and back into the same lobby repeats nothing; a different lobby re-arms
 * all three.
 *
 * <p>Pure: inputs come in as arguments, so the rules are testable without a client.
 */
public final class HollowsLobbyReminder {

    private static final HollowsLobbyReminder INSTANCE = new HollowsLobbyReminder();

    /** The island the reminder applies to, as {@code SkyBlockLocation.onIsland} resolves it. */
    public static final String ISLAND = "Crystal Hollows";

    /** Where a lobby stands relative to its cutoff. */
    public enum Stage {
        /** Clock unknown, or the cutoff is further away than the lead window. */
        NONE,
        /** Inside the lead window: warps still work, not for much longer. */
        CLOSING,
        /** The cutoff day has been reached: nobody can warp in. */
        CLOSED
    }

    /** Which notice. */
    public enum Kind {
        JOIN, LEAD, CLOSED
    }

    /** One reminder to deliver: the headline and the note under it. */
    public record Notice(Kind kind, String name, String note) {
    }

    /** The settings the decision depends on, as one value. */
    public record Settings(int closeDay, int warnMinutes, boolean onJoin, boolean onLead,
                           boolean onClosed) {
    }

    private String lobby;
    private boolean seen;
    private boolean announcedLead;
    private boolean announcedClosed;

    HollowsLobbyReminder() {
    }

    public static HollowsLobbyReminder getInstance() {
        return INSTANCE;
    }

    /**
     * The stage a lobby is in.
     *
     * @param clockTicks  the server's day-night clock; anything below zero is unknown
     * @param closeDay    the first lobby day on which warps are refused
     * @param warnMinutes the lead window in minutes of clock (20 to a day); {@code 0} = no window
     */
    public static Stage stageOf(long clockTicks, int closeDay, int warnMinutes) {
        if (clockTicks < 0L || closeDay <= 0) {
            return Stage.NONE;
        }
        long closeAt = HollowsLobbyClock.closeAtTicks(closeDay);
        if (clockTicks >= closeAt) {
            return Stage.CLOSED;
        }
        if (warnMinutes > 0
                && closeAt - clockTicks <= warnMinutes * HollowsLobbyClock.TICKS_PER_CLOCK_MINUTE) {
            return Stage.CLOSING;
        }
        return Stage.NONE;
    }

    /**
     * How far into the lead window the clock is: {@code 0} at its start, {@code 1} at the cutoff,
     * above 1 past it, below 0 before the window - what the card's colour follows.
     */
    public static double progress(long clockTicks, int closeDay, int warnMinutes) {
        long closeAt = HollowsLobbyClock.closeAtTicks(closeDay);
        long window = Math.max(1L, warnMinutes * HollowsLobbyClock.TICKS_PER_CLOCK_MINUTE);
        return 1.0 - (double) (closeAt - clockTicks) / window;
    }

    /**
     * What to announce now, or {@code null}.
     *
     * @param lobbyKey       the current lobby (its server name); {@code null} while unknown
     * @param inHollows      whether the player is on the Crystal Hollows
     * @param clockTicks     the lobby clock; below zero while unknown
     * @param ticksPerSecond clock ticks per real second, for the ETA ({@code <= 0}: no estimate)
     */
    public synchronized Notice check(String lobbyKey, boolean inHollows, long clockTicks,
                                     double ticksPerSecond, Settings settings) {
        if (!inHollows || lobbyKey == null) {
            return null;
        }
        if (!lobbyKey.equals(lobby)) {
            lobby = lobbyKey;
            seen = false;
            announcedLead = false;
            announcedClosed = false;
        }
        if (clockTicks < 0L) {
            return null;
        }
        boolean first = !seen;
        seen = true;
        int closeDay = settings.closeDay();
        Stage stage = stageOf(clockTicks, closeDay, settings.warnMinutes());
        if (stage == Stage.CLOSED) {
            if (announcedClosed) {
                return null;
            }
            announcedClosed = true;
            announcedLead = true;
            return settings.onClosed() ? closed(closeDay) : null;
        }
        if (stage == Stage.CLOSING && !announcedLead) {
            announcedLead = true;
            long seconds = HollowsLobbyClock.secondsUntil(clockTicks, closeDay, ticksPerSecond);
            if (first && settings.onJoin()) {
                return join(clockTicks, closeDay, seconds);
            }
            return settings.onLead() ? lead(closeDay, seconds) : null;
        }
        return null;
    }

    private static Notice join(long clockTicks, int closeDay, long seconds) {
        String until = seconds < 0L
                ? "the lobby clock is not moving, so no time estimate"
                : HollowsLobbyClock.etaText(seconds) + " until day " + closeDay;
        return new Notice(Kind.JOIN,
                "Crystal Hollows lobby day " + HollowsLobbyClock.dayText(clockTicks)
                        + ": closes to warps soon",
                until + " - bring your party in now, and think twice before leaving");
    }

    private static Notice lead(int closeDay, long seconds) {
        String when = seconds < 0L ? "soon" : "in " + HollowsLobbyClock.etaText(seconds);
        return new Notice(Kind.LEAD,
                "Crystal Hollows lobby closes to warps " + when + " (day " + closeDay + ")",
                "bring your party in now");
    }

    private static Notice closed(int closeDay) {
        return new Notice(Kind.CLOSED, "Crystal Hollows lobby is closing to warps",
                "day " + closeDay + ": if you leave, you won't get back in - nor will your party");
    }

    /** One line for the settings page and the card: where the current lobby stands. */
    public static String status(boolean inHollows, long clockTicks, double ticksPerSecond,
                                int closeDay) {
        if (!inHollows) {
            return "not in the Crystal Hollows";
        }
        if (clockTicks < 0L) {
            return "lobby clock not reported yet";
        }
        String day = "day " + HollowsLobbyClock.dayText(clockTicks);
        if (stageOf(clockTicks, closeDay, 0) == Stage.CLOSED) {
            return day + " - closed to warps";
        }
        long seconds = HollowsLobbyClock.secondsUntil(clockTicks, closeDay, ticksPerSecond);
        return seconds < 0L
                ? day + " - clock stopped"
                : day + " - closes to warps in " + HollowsLobbyClock.etaText(seconds);
    }
}
