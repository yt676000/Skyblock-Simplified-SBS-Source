/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.chocolate.logic;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls numbers out of Chocolate Factory lore, structurally rather than by remembered wording.
 *
 * <p><b>Nothing in here is {@code CONFIRMED}.</b> No session has opened the factory with
 * {@code /sbs probe} armed, so the keyword defaults are {@code ESTIMATED} and live in config where
 * one visit can correct them. What keeps that honest is the shape of the failure: a line that does
 * not match yields "unknown", the slot is dropped from the ranking, and the panel shows fewer
 * entries. It can never invent a cost or a gain, so the recommendation is either right or absent.
 *
 * <p><b>Structural, not transcribed.</b> A cost is "a number on a line that also carries a cost
 * word"; a gain is "a {@code +number} on a line that also says per second". That survives Hypixel
 * rewording the sentence around it, which a remembered full-line regex does not.
 *
 * <p><b>Both number shapes are accepted</b> - grouped ({@code 1,234,567}) and suffixed
 * ({@code 1.23M}). Hypixel writes the first; the second costs one branch and removes a whole class
 * of silent miss. Note that item lore is <i>not</i> rewritten by this mod the way chat is (see
 * {@code docs/issues/core.md}, "the chat parsers were reading text this mod had rewritten") - the
 * suffix branch is defensive, not a workaround for us.
 */
public final class ChocolateLore {

    /** Words that mark a line as stating a price. ESTIMATED. */
    public static final String DEFAULT_COST_WORDS = "cost, price, costs";

    /** Words that mark a line as stating a rate. ESTIMATED. */
    public static final String DEFAULT_RATE_WORDS = "per second, /s, each second";

    /**
     * A number with an optional magnitude suffix: {@code 1234}, {@code 1,234,567}, {@code 12.5},
     * {@code 1.23M}, {@code 450k}.
     *
     * <p>One pattern rather than two, because two means "find a suffixed number anywhere, else a
     * plain one" - and on {@code Cost: 1,000 (2.5M all time)} that reads the wrong number
     * entirely. One pattern takes whichever comes <b>first</b>, which is what "the number on this
     * line" means.
     *
     * <p>The suffix may not be followed by a letter, and that guard is the whole reason it is
     * written out: without it {@code 3 boosts} parses as three billion, and {@code 2 tiers} as two
     * trillion. Every one of {@code k m b t} starts an ordinary English word.
     */
    private static final Pattern NUMBER =
            Pattern.compile("(\\d[\\d,]*(?:\\.\\d+)?)(?:\\s*([kKmMbBtT]))?(?![A-Za-z])");

    /** The same, but requiring the {@code +} that distinguishes "this adds" from "this is". */
    private static final Pattern ADDED =
            Pattern.compile("\\+\\s*(\\d[\\d,]*(?:\\.\\d+)?)(?:\\s*([kKmMbBtT]))?(?![A-Za-z])");

    /** A capacity pair: "123/150". */
    private static final Pattern CAPACITY =
            Pattern.compile("(\\d[\\d,]*)\\s*/\\s*(\\d[\\d,]*)");

    /** What every reader returns when the lore did not yield the value. */
    public static final double UNKNOWN = -1;

    private ChocolateLore() {
    }

    /** The first number on a line, with its magnitude suffix applied, or {@link #UNKNOWN}. */
    public static double number(String line) {
        if (line == null || line.isEmpty()) {
            return UNKNOWN;
        }
        Matcher match = NUMBER.matcher(line);
        return match.find() ? scale(parse(match.group(1)), match.group(2)) : UNKNOWN;
    }

    /**
     * The cost stated somewhere in {@code lore}, or {@link #UNKNOWN}.
     *
     * @param costWords comma-separated keywords that mark a line as a price line
     */
    public static double cost(List<String> lore, String costWords) {
        String line = lineWith(lore, costWords);
        return line == null ? UNKNOWN : number(line);
    }

    /**
     * The added-per-second figure stated in {@code lore}, or {@link #UNKNOWN}.
     *
     * <p>Requires <b>both</b> a rate word and an explicit {@code +}. A line saying what the
     * upgrade produces in total, or what the factory already makes, carries a rate word too - the
     * plus sign is what distinguishes "this adds" from "this is", and reading the second as the
     * first would put an already-owned figure into a payback division.
     */
    public static double addedPerSecond(List<String> lore, String rateWords) {
        String line = lineWith(lore, rateWords);
        if (line == null) {
            return UNKNOWN;
        }
        Matcher added = ADDED.matcher(line);
        if (!added.find()) {
            return UNKNOWN;
        }
        return scale(parse(added.group(1)), added.group(2));
    }

    /**
     * The plain rate stated in {@code lore} - the factory's own output, with no {@code +}.
     *
     * @return the rate, or {@link #UNKNOWN}
     */
    public static double perSecond(List<String> lore, String rateWords) {
        String line = lineWith(lore, rateWords);
        return line == null ? UNKNOWN : number(line);
    }

    /**
     * A {@code current/max} pair found anywhere in {@code lore}, for the barn and the Time Tower.
     *
     * @return {@code {current, max}}, or {@code null} when no such pair is there
     */
    public static long[] capacity(List<String> lore) {
        if (lore == null) {
            return null;
        }
        for (String line : lore) {
            if (line == null) {
                continue;
            }
            Matcher pair = CAPACITY.matcher(line);
            if (pair.find()) {
                return new long[] {(long) parse(pair.group(1)), (long) parse(pair.group(2))};
            }
        }
        return null;
    }

    /** The first line containing any of the comma-separated {@code words}, or {@code null}. */
    public static String lineWith(List<String> lore, String words) {
        if (lore == null) {
            return null;
        }
        for (String line : lore) {
            if (line != null && containsAny(line.toLowerCase(Locale.ROOT), words)) {
                return line;
            }
        }
        return null;
    }

    private static boolean containsAny(String lowerLine, String words) {
        if (words == null) {
            return false;
        }
        for (String raw : words.split(",")) {
            String word = raw.trim().toLowerCase(Locale.ROOT);
            if (!word.isEmpty() && lowerLine.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static double parse(String digits) {
        try {
            return Double.parseDouble(digits.replace(",", ""));
        } catch (NumberFormatException e) {
            return UNKNOWN;
        }
    }

    private static double scale(double value, String suffix) {
        if (value == UNKNOWN || suffix == null || suffix.isEmpty()) {
            return value;
        }
        return switch (Character.toLowerCase(suffix.charAt(0))) {
            case 'k' -> value * 1_000d;
            case 'm' -> value * 1_000_000d;
            case 'b' -> value * 1_000_000_000d;
            case 't' -> value * 1_000_000_000_000d;
            default -> value;
        };
    }
}
