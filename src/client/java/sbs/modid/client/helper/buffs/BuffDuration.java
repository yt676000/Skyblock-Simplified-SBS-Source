/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.buffs;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Re-words the buff durations {@link BuffTracker} reads off the tab list.
 *
 * <p>Hypixel writes these two timers in whatever shape it feels like - "3 years, 2 months" for a
 * cookie, "4 hours" for a god potion, sometimes the short "1d 4h". In a sidebar row that prose is
 * expensive: it is the longest thing on the panel and it makes the scoreboard's width jump around as
 * the buff ticks down. So the number/unit pairs are pulled out and written again in one consistent
 * style - short by default ({@code 3y 3m 3d 3min 3s}), fully spelled out when the player prefers
 * that.
 *
 * <p><b>Nothing is invented.</b> Only the units the server actually sent are printed, in the order
 * they were sent; no zero rows are padded in and no missing seconds are counted down. If a value
 * cannot be read as a duration at all - an unknown unit word, a shape never seen before - the
 * server's own text is handed back untouched, which is the same honesty rule {@link BuffTracker}
 * follows.
 */
public final class BuffDuration {

    /** "3 years", "2mon", "45 s" - a count followed by its unit word. */
    private static final Pattern PART = Pattern.compile("(\\d+)\\s*([A-Za-z]+)");

    private BuffDuration() {
    }

    /** The units Hypixel words these timers in, longest first - the order they are printed in. */
    private enum Unit {
        // A year and a month are calendar-free approximations (365 and 30 days). They only ever
        // come from the cookie's "3 years, 3 months", whose precision is a month anyway.
        YEAR("y", "year", 365L * 86_400_000L),
        // Prints as "m" while minutes print as "min" - so the two stay distinct on the panel. Only
        // the OUTPUT is "m": unitOf still reads a bare "m" INPUT as minutes, which is how Hypixel
        // spells them, and our output is never fed back into the parser.
        MONTH("m", "month", 30L * 86_400_000L),
        WEEK("w", "week", 7L * 86_400_000L),
        DAY("d", "day", 86_400_000L),
        HOUR("h", "hour", 3_600_000L),
        MINUTE("min", "minute", 60_000L),
        SECOND("s", "second", 1_000L);

        final String shortName;
        final String longName;
        final long millis;

        Unit(String shortName, String longName, long millis) {
            this.shortName = shortName;
            this.longName = longName;
            this.millis = millis;
        }
    }

    /**
     * A duration read as a number: the total, and the size of the smallest unit the text named -
     * "16 hours" is {@code 16h} give or take an hour, "28h 48m" is exact to the minute.
     */
    public record Parsed(long millis, long precisionMs) {
    }

    /** "12:34" (m:ss), "1:02:03" (h:mm:ss), optionally in parentheses as lore prints them. */
    private static final Pattern CLOCK =
            Pattern.compile("^\\(?\\s*(?:(\\d{1,3}):)?(\\d{1,3}):([0-5]\\d)\\s*\\)?$");
    /** What may sit between the number/unit pairs: commas, "and", whitespace, brackets. */
    private static final Pattern FILLER = Pattern.compile("(?i)[\\s,()]+|\\band\\b");

    /**
     * Reads a duration in any shape these timers have been seen in: {@code 28h 48m},
     * {@code 30 Minutes}, {@code 60s}, {@code 16 hours}, {@code 2 days, 5 hours},
     * {@code 3 years, 3 months}, {@code 12:34}, {@code 1:02:03}, {@code (00:30:00)}.
     *
     * <p>Strict: every character must belong to a number/unit pair, a clock, or the filler between
     * pairs. "Haste III", "Not active!" and "less than a minute" are not durations, and a partial
     * reading of a sentence is never returned.
     *
     * @return the reading, or {@code null} when {@code text} is not a duration. Never throws.
     */
    public static Parsed parse(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        if (value.isEmpty()) {
            return null;
        }
        Matcher clock = CLOCK.matcher(value);
        if (clock.matches()) {
            try {
                long hours = clock.group(1) == null ? 0 : Long.parseLong(clock.group(1));
                long minutes = Long.parseLong(clock.group(2));
                long seconds = Long.parseLong(clock.group(3));
                return new Parsed(((hours * 60 + minutes) * 60 + seconds) * 1_000L, 1_000L);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        long total = 0;
        long precision = 0;
        boolean any = false;
        int consumed = 0;
        StringBuilder rest = new StringBuilder();
        Matcher m = PART.matcher(value);
        while (m.find()) {
            rest.append(value, consumed, m.start());
            consumed = m.end();
            Unit unit = unitOf(m.group(2));
            if (unit == null) {
                return null;
            }
            long amount;
            try {
                amount = Long.parseLong(m.group(1));
                total = Math.addExact(total, Math.multiplyExact(amount, unit.millis));
            } catch (NumberFormatException | ArithmeticException e) {
                return null;
            }
            precision = precision == 0 ? unit.millis : Math.min(precision, unit.millis);
            any = true;
        }
        rest.append(value.substring(consumed));
        if (!any || !FILLER.matcher(rest).replaceAll("").isEmpty()) {
            return null;
        }
        return new Parsed(total, precision);
    }

    /**
     * @param value    the tab list's wording, or {@code null}
     * @param longForm spell the units out ("3 years, 2 months") instead of the short form ("3y 2m")
     * @return the re-worded duration, the input unchanged when it is not one, or {@code null}
     */
    public static String format(String value, boolean longForm) {
        if (value == null || value.isBlank()) {
            return value;
        }
        List<String> parts = new ArrayList<>(6);
        Matcher m = PART.matcher(value);
        while (m.find()) {
            Unit unit = unitOf(m.group(2));
            if (unit == null) {
                return value; // a word we do not know: better the server's text than a wrong reading
            }
            long amount;
            try {
                amount = Long.parseLong(m.group(1));
            } catch (NumberFormatException e) {
                return value; // a count too large to be a duration
            }
            parts.add(longForm
                    ? amount + " " + unit.longName + (amount == 1 ? "" : "s")
                    : amount + unit.shortName);
        }
        if (parts.isEmpty()) {
            return value; // no numbers at all - prose like "less than a minute"
        }
        return String.join(longForm ? ", " : " ", parts);
    }

    /**
     * The unit a word names, or {@code null}. Bare {@code m} is minutes, not months - that is how
     * Hypixel's own short form reads it, and a month printed as a minute would be a wild lie.
     */
    private static Unit unitOf(String word) {
        String w = word.toLowerCase(Locale.ROOT);
        return switch (w) {
            case "y", "yr", "yrs", "year", "years" -> Unit.YEAR;
            case "mo", "mon", "mons", "month", "months" -> Unit.MONTH;
            case "w", "wk", "wks", "week", "weeks" -> Unit.WEEK;
            case "d", "day", "days" -> Unit.DAY;
            case "h", "hr", "hrs", "hour", "hours" -> Unit.HOUR;
            case "m", "min", "mins", "minute", "minutes" -> Unit.MINUTE;
            case "s", "sec", "secs", "second", "seconds" -> Unit.SECOND;
            default -> null;
        };
    }
}
