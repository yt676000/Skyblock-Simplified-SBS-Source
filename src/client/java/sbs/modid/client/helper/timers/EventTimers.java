/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.timers;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.tab.TabWidgets;
import sbs.modid.client.helper.scoreboard.ScoreboardLine;
import sbs.modid.client.helper.scoreboard.ScoreboardReader;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The SkyBlock event clock: what is running now and what is next.
 *
 * <p><b>The rule this class is built around:</b> never invent a number. Hypixel publishes far less
 * than a timer overlay would like, so every row is tagged with how it was arrived at
 * ({@link TimerEntry.Certainty}) and the card marks anything that is not measured. A countdown the
 * player trusts and then misses is worse than no countdown at all.
 *
 * <p>What that means per row:
 * <ul>
 *   <li><b>Lobby age</b> is the server's own day-night clock in days - what players already use to
 *       judge a Crystal Hollows lobby. Exact, free, no parsing, and read off the time packet rather
 *       than out of the level; {@link #lobbyDay()} and {@link ServerWorldTime} say why that
 *       distinction is the whole feature.</li>
 *   <li><b>Dark Auction</b> runs every 3 SkyBlock days at midnight, and 3 SkyBlock days are exactly
 *       one real hour, so it lands on the same minute past every real hour. That minute is a config
 *       value rather than a constant, because it is the one part that could drift.</li>
 *   <li><b>Jacob's Contest</b> shares that 3-day cadence but not its phase, and the phase is not
 *       derivable from anything on screen. So it is <i>learned</i>, from either of two sources: the
 *       first contest we see running pins it, and so does the SkyBlock calendar if the player opens
 *       it ({@link SkyblockCalendar}), which is Hypixel's own list of the coming contests and
 *       therefore settles it without the wait. Until one of them happens the row says so instead of
 *       guessing.</li>
 *   <li><b>Volcano</b> erupts on a 120-150 minute spread - genuinely unpredictable - but Hypixel puts
 *       the current eruption in a tab widget, so that text is shown and no countdown is offered.</li>
 * </ul>
 *
 * <p>The SkyBlock date comes off the sidebar, which is also what makes the contest maths possible.
 * Anything unparsed lands in the {@code [SBS][Timers]} log so the patterns can be tuned live.
 */
public final class EventTimers {

    private static final EventTimers INSTANCE = new EventTimers();

    private static final long SCAN_INTERVAL_MS = 1_000L;

    /** A SkyBlock day is 20 real minutes; a SkyBlock hour is 50 real seconds. */
    private static final int REAL_SECONDS_PER_SB_DAY = 1_200;
    private static final int SB_MINUTES_PER_DAY = 24 * 60;
    private static final int DAYS_PER_MONTH = 31;
    /** Dark Auction and Jacob's Contest both run on a 3-SkyBlock-day cycle. */
    private static final int EVENT_CYCLE_DAYS = 3;

    /** The twelve SkyBlock months, in order; the index is what the day-of-year maths uses. */
    private static final List<String> MONTHS = List.of(
            "early spring", "spring", "late spring",
            "early summer", "summer", "late summer",
            "early autumn", "autumn", "late autumn",
            "early winter", "winter", "late winter");

    /**
     * "Late Summer 22nd" on the sidebar. Deliberately unanchored at the end: Hypixel appends symbols
     * and spacing to these lines, and an exact full-line match silently never synced the clock.
     */
    private static final Pattern DATE = Pattern.compile(
            "(?i)\\b((?:early |late )?(?:spring|summer|autumn|winter))\\s+(\\d{1,2})(?:st|nd|rd|th)?\\b");
    /** "12:30am" on the sidebar (the trailing marker Hypixel appends is ignored). */
    private static final Pattern TIME = Pattern.compile("(?i)^(\\d{1,2}):(\\d{2})\\s*(am|pm)\\b");
    /** Any line naming Jacob's contest, wherever Hypixel decides to put it. */
    private static final Pattern JACOB = Pattern.compile("(?i)jacob|farming contest");
    private static final Pattern VOLCANO = Pattern.compile("(?i)^volcano\\s*:?\\s*(.*)$");

    /**
     * The SkyBlock clock is <b>anchored, not sampled</b>: the sidebar sets it, and from then on it is
     * extrapolated from real elapsed time. SkyBlock time runs at a fixed rate (a day is 20 real
     * minutes), so once the offset is known the clock needs no further reads.
     *
     * <p>Sampling was the bug behind "the Jacob countdown pauses": the numbers only moved while the
     * sidebar happened to be readable, so the moment it or the tab widget went away the countdown
     * froze at its last value instead of continuing.
     */
    private volatile long syncRealMs;
    private volatile int syncDayOfYear = -1;
    private volatile int syncMinuteOfDay = -1;

    /** The sidebar value read last, so a step to a new one can be recognised. */
    private long lastShownMinutes = Long.MIN_VALUE;

    /**
     * The sidebar clock advances in steps, not continuously - it shows 12:30am for several real
     * seconds, then jumps to 12:40am. Its value is therefore a <b>floor</b> of the real SkyBlock
     * time, off by up to one step.
     *
     * <p>Only the resync tolerance below is derived from this number; a step is <i>recognised</i> by
     * the shown value changing, not by its size. So if Hypixel's granularity is not what this says,
     * corrections merely become slightly more or less eager - nothing breaks.
     */
    private static final int SIDEBAR_STEP_SB_MINUTES = 10;

    /**
     * How far the free-running clock may sit from a freshly stepped sidebar value before it is
     * corrected. Only has to cover the scan interval (1 real second = 1.2 SkyBlock minutes), since
     * at the instant of a step the shown value is exact.
     */
    private static final int STEP_TOLERANCE_SB_MINUTES = 2;

    /**
     * Drift that no longer looks like the sidebar's own coarseness but like a genuinely different
     * clock (a lobby swap, a long freeze), and is resynced whenever it is seen.
     */
    private static final int RESYNC_TOLERANCE_SB_MINUTES = SIDEBAR_STEP_SB_MINUTES + 20;

    private volatile String jacobLine;
    private volatile String volcanoLine;

    private long lastScanAt;
    private long lastLogAt;

    /** The calendar is scanned once a second while it is open; its log must not be. */
    private static final long CALENDAR_LOG_INTERVAL_MS = 30_000L;
    private long lastCalendarLogAt;

    private EventTimers() {
    }

    public static EventTimers getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.TimersSettings cfg() {
        return ConfigManager.getInstance().get().timers;
    }

    // ------------------------------------------------------------------ capture

    /** Called every client tick; throttles itself to once a second. */
    public void onClientTick() {
        if (!cfg().enabled) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastScanAt < SCAN_INTERVAL_MS) {
            return;
        }
        lastScanAt = now;
        scan(now);
    }

    private void scan(long now) {
        int day = -1;
        int minute = -1;
        String jacob = null;
        String volcano = null;

        for (ScoreboardLine line : ScoreboardReader.lines()) {
            String text = line.stripped() == null ? "" : line.stripped().trim();
            if (text.isEmpty()) {
                continue;
            }
            int dated = dayOfYearIn(text);
            if (dated > 0) {
                day = dated;
                continue;
            }
            Matcher t = TIME.matcher(text);
            if (t.find()) {
                minute = toMinuteOfDay(Integer.parseInt(t.group(1)), Integer.parseInt(t.group(2)),
                        t.group(3));
                continue;
            }
            if (JACOB.matcher(text).find()) {
                jacob = text;
            }
        }
        for (String line : TabWidgets.lines()) {
            Matcher v = VOLCANO.matcher(line.trim());
            if (v.matches() && !v.group(1).isBlank()) {
                volcano = v.group(1).trim();
            } else if (jacob == null && JACOB.matcher(line).find()) {
                jacob = line.trim();
            }
        }

        if (day > 0 && minute >= 0) {
            adjustClock(day, minute, now);
        }
        jacobLine = jacob;
        volcanoLine = volcano;

        // Watching a contest run pins the cycle's phase - remember it for good.
        int today = currentDayOfYear();
        if (jacob != null && today > 0) {
            pinJacobPhase(today % EVENT_CYCLE_DAYS, "a running contest");
        }
        // ...and so does the calendar, if the player happens to have it open, without the wait.
        readCalendar(now);
        // The event calendar: refresh the tab widget reading and fire a due start alert.
        CalendarEvents.getInstance().tick(minuteOfYear());

        if (syncDayOfYear < 0 && now - lastLogAt > 30_000L && ScoreboardReader.hasSidebar()) {
            lastLogAt = now;
            List<String> raw = new ArrayList<>();
            for (ScoreboardLine l : ScoreboardReader.lines()) {
                raw.add(l.stripped());
            }
            sbs.modid.SkyblockSimplifiedSBS.LOGGER.info("[SBS][Timers] no SkyBlock date in sidebar={}", raw);
        }
    }

    /**
     * The SkyBlock day-of-year (1-based) named anywhere in {@code text}, or {@code -1}. Shared with
     * {@link SkyblockCalendar}, which has to read "Late Summer 24th" out of an item exactly the way
     * this reads it off the sidebar — two parsers would be two chances to disagree about what day a
     * contest is on.
     */
    static int dayOfYearIn(String text) {
        if (text == null || text.isEmpty()) {
            return -1;
        }
        Matcher match = DATE.matcher(text);
        if (!match.find()) {
            return -1;
        }
        int month = MONTHS.indexOf(match.group(1).toLowerCase(Locale.ROOT));
        int dayOfMonth = Integer.parseInt(match.group(2));
        if (month < 0 || dayOfMonth < 1 || dayOfMonth > DAYS_PER_MONTH) {
            return -1;
        }
        return month * DAYS_PER_MONTH + dayOfMonth;
    }

    /**
     * Takes the contest days the open calendar names and turns them into the cycle's phase.
     *
     * <p><b>The days check each other.</b> A calendar shows several contests, and every one of them
     * must land on the same remainder — that is what "every third day" means. So a set that
     * disagrees is not a calendar we read correctly, and it is dropped whole rather than trusted for
     * one entry: a wrong phase is a countdown that is confidently wrong for the rest of the session,
     * which is worse than the row admitting it does not know yet.
     *
     * <p>The remainder survives the year end, because a SkyBlock year is 372 days and 372 divides by
     * three — a contest day this year is a contest day next year.
     */
    private void readCalendar(long now) {
        SkyblockCalendar.Reading reading = SkyblockCalendar.read();
        if (reading.isEmpty()) {
            return;
        }
        if (reading.contestDays().isEmpty()) {
            if (now - lastCalendarLogAt > CALENDAR_LOG_INTERVAL_MS) {
                lastCalendarLogAt = now;
                sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                        "[SBS][Timers] calendar names a contest but carries no date: {}",
                        reading.undatedExample());
            }
            return;
        }
        int phase = Math.floorMod(reading.contestDays().get(0), EVENT_CYCLE_DAYS);
        for (int contestDay : reading.contestDays()) {
            if (Math.floorMod(contestDay, EVENT_CYCLE_DAYS) != phase) {
                if (now - lastCalendarLogAt > CALENDAR_LOG_INTERVAL_MS) {
                    lastCalendarLogAt = now;
                    sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                            "[SBS][Timers] calendar contest days disagree on the 3-day cycle, "
                                    + "ignoring them: {}", reading.contestDays());
                }
                return;
            }
        }
        pinJacobPhase(phase, "the calendar");
    }

    /** Stores a newly learned phase, saying in the log where it came from. */
    private void pinJacobPhase(int phase, String source) {
        if (cfg().jacobPhase == phase) {
            return;
        }
        sbs.modid.SkyblockSimplifiedSBS.LOGGER.info("[SBS][Timers] Jacob cycle phase {} from {}",
                phase, source);
        cfg().jacobPhase = phase;
        ConfigManager.getInstance().save();
    }

    /** "12:30am" -> minutes since SkyBlock midnight; 12am is 0 and 12pm is noon. */
    private static int toMinuteOfDay(int hour12, int minute, String marker) {
        int hour = hour12 % 12;
        if (marker.equalsIgnoreCase("pm")) {
            hour += 12;
        }
        return hour * 60 + minute;
    }

    // ------------------------------------------------------------------ rows

    /** Every enabled row, in display order. */
    public List<TimerEntry> entries() {
        var cfg = cfg();
        List<TimerEntry> rows = new ArrayList<>(6);
        if (cfg.showLobbyAge) {
            long day = lobbyDay();
            if (day >= 0) {
                rows.add(TimerEntry.measured("Lobby", "Day " + day));
            }
        }
        if (cfg.showDarkAuction) {
            rows.add(TimerEntry.derived("Dark Auction", shortDuration(secondsToDarkAuction())));
        }
        if (cfg.showJacobContest) {
            rows.add(jacobRow());
        }
        if (cfg.showVolcano) {
            String volcano = volcanoLine;
            if (volcano != null) {
                rows.add(TimerEntry.measured("Volcano", volcano));
            }
        }
        if (cfg.showSkyblockDate && currentDayOfYear() > 0) {
            rows.add(TimerEntry.measured("SkyBlock", dateText()));
        }
        if (cfg.showCalendar) {
            rows.addAll(CalendarEvents.getInstance().rows(minuteOfYear()));
        }
        return rows;
    }

    /**
     * The lobby's age in days: how long the server instance you are on has been up. It is the
     * <b>day-night clock</b>, and only ever the value the server itself sent for it - see
     * {@link ServerWorldTime} for why the level's own accessor cannot be trusted, and for what
     * happens to the row when a server never sends one.
     *
     * <p><b>Not game time</b>, the other counter in the same packet. Game time belongs to the
     * <i>world save</i> Hypixel boots an island from and is inherited by every instance made out of
     * it: two hubs sampled minutes apart read 1,248,069,618 and 1,248,064,413 while a Crystal
     * Hollows read 60,563,502 - it put that lobby at day 2519.
     *
     * <p><b>Identical readings across lobbies are real, not a bug.</b> Hypixel restarts SkyBlock
     * servers in waves, so hopping during one gives the same number everywhere. Three servers
     * sampled inside two minutes (mini70G Hub, mini24CD Crystal Hollows, mini5AH Hub) all read day
     * 11 - subtracting each reading from its timestamp puts their boots at 19:20:02, 19:20:38 and
     * 19:20:01. The proof that this is per-server and not one network-wide clock is the main
     * Hypixel lobby in the same log: day 1 at 23:07:03, a boot at 22:40, while SkyBlock sat at day
     * 11. A shared clock cannot read two values at one moment.
     */
    private static long lobbyDay() {
        long ticks = ServerWorldTime.overworldClockTicks();
        return ticks < 0L ? -1L : ticks / ServerWorldTime.DAY_TICKS;
    }

    /**
     * Seconds until the next Dark Auction. Three SkyBlock days are exactly one real hour, so the
     * auction lands on the same minute past every real hour.
     */
    private static int secondsToDarkAuction() {
        int target = Math.floorMod(cfg().darkAuctionMinute, 60);
        LocalTime now = LocalTime.now();
        int seconds = (target - now.getMinute()) * 60 - now.getSecond();
        return seconds <= 0 ? seconds + 3_600 : seconds;
    }

    // ------------------------------------------------------------------ the SkyBlock clock

    /** SkyBlock days in a year: twelve months of 31. */
    private static final int DAYS_PER_YEAR = 12 * DAYS_PER_MONTH;
    /** SkyBlock minutes that pass per real second: 1440 in a day, over 1200 real seconds. */
    private static final double SB_MINUTES_PER_REAL_SECOND =
            SB_MINUTES_PER_DAY / (double) REAL_SECONDS_PER_SB_DAY;

    /**
     * Uses the in-game time to <b>correct</b> the running clock, never as the clock itself.
     *
     * <p>Re-anchoring on every read was what made the Jacob row jump instead of counting down: the
     * sidebar time only steps every {@value #SIDEBAR_STEP_SB_MINUTES} SkyBlock minutes, so its value
     * is a floor that lags the truth by up to one step - a little over 8 real seconds. Writing it
     * back once a second dragged the smoothly extrapolated countdown back onto that stale floor again
     * and again, and the display hopped by the amount it had advanced in between.
     *
     * <p>So the clock free-runs, and the sidebar only intervenes in three cases:
     * <ul>
     *   <li><b>Nothing to run from yet</b> - the first read has to set it, floor and all.</li>
     *   <li><b>The sidebar just stepped.</b> That instant is the one moment its value is exact, so a
     *       correction there is accurate rather than a snap onto a floor. In practice this fires once
     *       after joining (removing the first read's error) and then finds nothing left to fix.</li>
     *   <li><b>The drift is too large to be the sidebar's coarseness</b> - a different lobby, a long
     *       freeze - where the running clock is simply wrong and is replaced.</li>
     * </ul>
     */
    private void adjustClock(int day, int minute, long now) {
        long shown = (long) (day - 1) * SB_MINUTES_PER_DAY + minute;
        boolean stepped = shown != lastShownMinutes;
        lastShownMinutes = shown;

        if (syncDayOfYear < 0) {
            setAnchor(day, minute, now);
            return;
        }
        long drift = Math.abs(driftFrom(shown));
        if (drift > (stepped ? STEP_TOLERANCE_SB_MINUTES : RESYNC_TOLERANCE_SB_MINUTES)) {
            setAnchor(day, minute, now);
        }
    }

    private void setAnchor(int day, int minute, long now) {
        syncDayOfYear = day;
        syncMinuteOfDay = minute;
        syncRealMs = now;
    }

    /**
     * How far the running clock is ahead of {@code shown}, in SkyBlock minutes, as the shortest
     * signed distance around the year - so a reading either side of new year is a small drift and
     * not a year-long one.
     */
    private long driftFrom(long shown) {
        long year = (long) DAYS_PER_YEAR * SB_MINUTES_PER_DAY;
        long diff = Math.floorMod(skyblockMinutesNow() - shown, year);
        return diff > year / 2 ? diff - year : diff;
    }

    /** Minutes since the start of SkyBlock day 1, extrapolated from the anchor; {@code -1} if unset. */
    private long skyblockMinutesNow() {
        if (syncDayOfYear < 0) {
            return -1;
        }
        long anchored = (long) (syncDayOfYear - 1) * SB_MINUTES_PER_DAY + syncMinuteOfDay;
        long elapsed = Math.round((System.currentTimeMillis() - syncRealMs) / 1000.0
                * SB_MINUTES_PER_REAL_SECOND);
        return anchored + elapsed;
    }

    /**
     * SkyBlock minutes since Early Spring 1st 00:00 right now, wrapped at the year end; {@code -1}
     * while the clock has not synced off the sidebar yet. Read-only, for the event calendar.
     */
    public long minuteOfYear() {
        long minutes = skyblockMinutesNow();
        return minutes < 0 ? -1 : Math.floorMod(minutes, (long) DAYS_PER_YEAR * SB_MINUTES_PER_DAY);
    }

    /** Today's day-of-year (1-based), wrapped at the year end; {@code -1} while unsynced. */
    private int currentDayOfYear() {
        long minutes = skyblockMinutesNow();
        return minutes < 0 ? -1 : (int) Math.floorMod(minutes / SB_MINUTES_PER_DAY, DAYS_PER_YEAR) + 1;
    }

    /** Minutes since SkyBlock midnight; {@code -1} while unsynced. */
    private int currentMinuteOfDay() {
        long minutes = skyblockMinutesNow();
        return minutes < 0 ? -1 : (int) Math.floorMod(minutes, SB_MINUTES_PER_DAY);
    }

    /**
     * Jacob's Contest.
     *
     * <p>A contest occupies a <b>whole SkyBlock day</b> - midnight to midnight, which is 20 real
     * minutes - and they fall every third day. So once the phase is known, both halves of the answer
     * follow from the clock: on a contest day the row counts down to its end, otherwise to the start
     * of the next one. The tab line is preferred while it is there because it is Hypixel's own
     * wording, but losing it no longer stops the countdown.
     */
    private TimerEntry jacobRow() {
        String active = jacobLine;
        if (active != null) {
            return TimerEntry.measured("Jacob", active);
        }
        int phase = cfg().jacobPhase;
        int dayOfYear = currentDayOfYear();
        if (phase < 0 || dayOfYear < 0) {
            return new TimerEntry("Jacob", "learning...", TimerEntry.Certainty.UNKNOWN);
        }
        if (dayOfYear % EVENT_CYCLE_DAYS == phase) {
            // Running right now: the interesting number is how long is left to farm.
            return new TimerEntry("Jacob", "ends " + shortDuration(secondsLeftInSkyblockDay()),
                    TimerEntry.Certainty.LEARNED);
        }
        // Contests start at SkyBlock midnight on every third day; walk forward to the next one.
        // secondsLeftInSkyblockDay already reaches the START of tomorrow, so only the days BEYOND
        // tomorrow are added - counting daysAhead whole days on top overshot by one SkyBlock day.
        int daysAhead = Math.floorMod(phase - dayOfYear, EVENT_CYCLE_DAYS);
        long seconds = (long) (daysAhead - 1) * REAL_SECONDS_PER_SB_DAY + secondsLeftInSkyblockDay();
        return new TimerEntry("Jacob", shortDuration(seconds), TimerEntry.Certainty.LEARNED);
    }

    /**
     * Real seconds left in the running Jacob's Contest by the learned cycle, or -1 when no contest is
     * running or the phase is not learned yet. The Jacob's Contest overlay reads its window from here
     * rather than keeping a second clock; it works even with the Timers card switched off, as long as
     * the phase was learned once.
     */
    public long jacobSecondsLeft() {
        int phase = cfg().jacobPhase;
        int dayOfYear = currentDayOfYear();
        if (phase < 0 || dayOfYear < 0 || dayOfYear % EVENT_CYCLE_DAYS != phase) {
            return -1;
        }
        return secondsLeftInSkyblockDay();
    }

    /** Real seconds until the next SkyBlock midnight. */
    private long secondsLeftInSkyblockDay() {
        int elapsed = Math.max(0, currentMinuteOfDay());
        return Math.round((SB_MINUTES_PER_DAY - elapsed)
                * (REAL_SECONDS_PER_SB_DAY / (double) SB_MINUTES_PER_DAY));
    }

    private String dateText() {
        int day = currentDayOfYear();
        String month = MONTHS.get(Math.min(MONTHS.size() - 1, (day - 1) / DAYS_PER_MONTH));
        int dayOfMonth = ((day - 1) % DAYS_PER_MONTH) + 1;
        String name = month.substring(0, 1).toUpperCase(Locale.ROOT) + month.substring(1);
        return name + " " + dayOfMonth;
    }

    /** "1h 04m" / "12m 40s" / "45s" - the coarsest form that still answers "do I have time". */
    static String shortDuration(long seconds) {
        if (seconds < 0) {
            return "-";
        }
        long h = seconds / 3_600;
        long m = (seconds % 3_600) / 60;
        long s = seconds % 60;
        if (h > 0) {
            return String.format(Locale.ROOT, "%dh %02dm", h, m);
        }
        if (m > 0) {
            return String.format(Locale.ROOT, "%dm %02ds", m, s);
        }
        return s + "s";
    }
}
