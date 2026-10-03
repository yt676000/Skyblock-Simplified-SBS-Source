/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.timers.model;

/**
 * When a calendar rule next runs, in SkyBlock time, with no Minecraft types - unit-tested.
 *
 * <p>The SkyBlock year: 12 months of 31 days, a day of 1440 SkyBlock minutes lasting 20 real minutes,
 * so 372 days are 124 real hours. Positions are minutes since Early Spring 1st, 00:00, wrapping at the
 * year's end - an event that runs across new year is simply one that wraps.
 */
public final class EventCalendar {

    public static final int DAY_MINUTES = 1440;
    public static final int DAYS_PER_MONTH = 31;
    public static final int DAYS_PER_YEAR = 12 * DAYS_PER_MONTH;
    public static final long YEAR_MINUTES = (long) DAYS_PER_YEAR * DAY_MINUTES;
    /** Real seconds per SkyBlock minute: 1200 real seconds over 1440 SkyBlock minutes. */
    public static final double REAL_SECONDS_PER_MINUTE = 1200.0 / DAY_MINUTES;

    /** Where a rule stands: running (minutes until it ends) or upcoming (minutes until it starts). */
    public record Occurrence(boolean running, long minutes) {

        public long realSeconds() {
            return Math.round(minutes * REAL_SECONDS_PER_MINUTE);
        }
    }

    private EventCalendar() {
    }

    /**
     * The running or next occurrence of {@code event} at {@code minuteOfYear}; {@code null} for a
     * tab-only event, which has no rule to compute from.
     */
    public static Occurrence next(CalendarData.Event event, long minuteOfYear) {
        if (event == null || !event.hasRule() || minuteOfYear < 0) {
            return null;
        }
        long now = Math.floorMod(minuteOfYear, YEAR_MINUTES);
        Occurrence best = null;
        if (event.starts != null) {
            for (int day : event.starts) {
                best = better(best, at(dayStart(day) + event.startMinute, event.lengthMinutes, now));
            }
        }
        if (event.monthDays != null) {
            for (int month = 0; month < 12; month++) {
                for (int day : event.monthDays) {
                    best = better(best, at(dayStart(month * DAYS_PER_MONTH + day) + event.startMinute,
                            event.lengthMinutes, now));
                }
            }
        }
        return best;
    }

    /** One start: running if now is inside [start, start + length) around the year, else how far off. */
    static Occurrence at(long start, int length, long now) {
        long since = Math.floorMod(now - start, YEAR_MINUTES);
        if (since < length) {
            return new Occurrence(true, length - since);
        }
        return new Occurrence(false, Math.floorMod(start - now, YEAR_MINUTES));
    }

    /** Running beats upcoming; among two of a kind, the sooner one. */
    private static Occurrence better(Occurrence a, Occurrence b) {
        if (a == null) {
            return b;
        }
        if (a.running() != b.running()) {
            return a.running() ? a : b;
        }
        return b.minutes() < a.minutes() ? b : a;
    }

    /** One calendar row: what to show, and whether the tab stated it (measured) or a rule computed it. */
    public record Row(CalendarData.Event event, boolean running, long realSeconds, boolean measured) {
    }

    /**
     * The row for one event. <b>The tab beats the rule</b>: when the widget names this event, its
     * countdown is Hypixel's own statement and is used as measured; otherwise the rule's computed
     * occurrence; {@code null} when there is neither (a tab-only event the tab is not showing).
     */
    public static Row resolve(CalendarData.Event event, long minuteOfYear, EventWidget.Reading tab) {
        if (tab != null && EventWidget.names(tab.name(), event.tabName)) {
            return new Row(event, tab.running(), tab.realSeconds(), true);
        }
        Occurrence computed = next(event, minuteOfYear);
        return computed == null ? null : new Row(event, computed.running(), computed.realSeconds(), false);
    }

    /** Minutes from the year's start to the start of a 1-based day of the year. */
    public static long dayStart(int dayOfYear) {
        return (long) (dayOfYear - 1) * DAY_MINUTES;
    }
}
