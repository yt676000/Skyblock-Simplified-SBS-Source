/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.events.logic;

import sbs.modid.client.skills.mining.events.model.MiningEvent;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Looks for the running event and its remaining time in the sidebar.
 *
 * <p><b>UNVERIFIED - no capture of an event on the sidebar exists.</b> Nothing here encodes a line
 * shape that has been seen. The event is recognised only by a known event <i>name</i> (those are
 * CONFIRMED from chat) appearing in a line, and the remaining time only by a line that starts with
 * one of a few labels and carries a duration. When neither matches, the reading is empty and the
 * chat lines carry the feature on their own - a wrong guess here costs nothing but a resync. Every
 * distinct sidebar line in the two islands is logged by the tracker so the real shape can replace
 * this; see {@code docs/features/mining-event-timer.md}.
 */
public final class MiningEventScoreboard {

    /**
     * What the sidebar said.
     *
     * @param event            the event a line named, or {@code null}
     * @param remainingSeconds seconds left from a labelled line, or {@code -1}
     */
    public record Reading(MiningEvent event, int remainingSeconds) {

        public static final Reading NONE = new Reading(null, -1);

        public boolean hasEvent() {
            return event != null;
        }
    }

    private static final Pattern REMAINING_LINE =
            Pattern.compile("(?i)^(?:remaining|time left|ends in)\\s*:?\\s*(.+)$");

    private static final Pattern UNITS =
            Pattern.compile("(?i)^(?:(\\d+)\\s*h)?\\s*(?:(\\d+)\\s*m)?\\s*(?:(\\d+)\\s*s)?$");

    private static final Pattern CLOCK = Pattern.compile("^(?:(\\d+):)?(\\d{1,2}):(\\d{2})$");

    private MiningEventScoreboard() {
    }

    /** Reads colour-stripped sidebar lines, in any order. */
    public static Reading read(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return Reading.NONE;
        }
        MiningEvent event = null;
        int remaining = -1;
        for (String line : lines) {
            String text = line == null ? "" : line.trim();
            if (text.isEmpty()) {
                continue;
            }
            if (event == null) {
                event = named(text);
            }
            if (remaining < 0) {
                Matcher matcher = REMAINING_LINE.matcher(text);
                if (matcher.matches()) {
                    remaining = parseDuration(matcher.group(1));
                }
            }
        }
        return event == null ? Reading.NONE : new Reading(event, remaining);
    }

    /** The known event whose name the line contains, or {@code null}. */
    static MiningEvent named(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (MiningEvent event : MiningEvent.values()) {
            if (event != MiningEvent.UNKNOWN
                    && lower.contains(event.displayName().toLowerCase(Locale.ROOT))) {
                return event;
            }
        }
        return null;
    }

    /**
     * Seconds in {@code 13m 20s}, {@code 1h 2m}, {@code 45s}, {@code 13:20} or {@code 1:02:03}; {@code -1}
     * for anything else, including an empty string.
     */
    public static int parseDuration(String text) {
        if (text == null) {
            return -1;
        }
        String value = text.trim();
        if (value.isEmpty()) {
            return -1;
        }
        Matcher clock = CLOCK.matcher(value);
        if (clock.matches()) {
            int hours = clock.group(1) == null ? 0 : Integer.parseInt(clock.group(1));
            return hours * 3600 + Integer.parseInt(clock.group(2)) * 60 + Integer.parseInt(clock.group(3));
        }
        Matcher units = UNITS.matcher(value);
        if (!units.matches() || (units.group(1) == null && units.group(2) == null && units.group(3) == null)) {
            return -1;
        }
        int seconds = 0;
        if (units.group(1) != null) {
            seconds += Integer.parseInt(units.group(1)) * 3600;
        }
        if (units.group(2) != null) {
            seconds += Integer.parseInt(units.group(2)) * 60;
        }
        if (units.group(3) != null) {
            seconds += Integer.parseInt(units.group(3));
        }
        return seconds;
    }
}
