/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.util;

/**
 * Reading the server's figures - {@code 1,234,567}, {@code 15.3K}, {@code 2.1M}, {@code -5} -
 * straight off a {@link CharSequence}, without the {@code replace(",", "")} copy, the regex
 * {@code replaceAll} or the substring that the per-entity and per-chat paths used to pay for.
 *
 * <p>Every method takes a {@code fallback} and returns it for anything it cannot read in full;
 * none of them throws, and none allocates on the way to a value. A range overload exists so a
 * caller holding a {@link java.util.regex.Matcher} can pass {@code start(g)}/{@code end(g)} rather
 * than {@code group(g)}.
 *
 * <h2>Grammar</h2>
 * {@code [-] digits-and-commas [. digits] [k|K|m|M|b|B]}, with surrounding spaces ignored. Commas
 * are grouping and are skipped wherever they fall between digits - the server is not consistent
 * about where it puts them, and neither were the {@code replace(",", "")} calls this replaces.
 * The value is computed exactly as {@code Double.parseDouble} would compute it on the comma-free
 * text, then scaled, so a caller switching over sees the same number.
 */
public final class NumberParse {

    /** Largest mantissa a double holds exactly; beyond it the cold exact path takes over. */
    private static final long EXACT_MANTISSA = 1L << 53;
    private static final double[] POW10 = {
            1e0, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9, 1e10, 1e11,
            1e12, 1e13, 1e14, 1e15, 1e16, 1e17, 1e18, 1e19, 1e20, 1e21, 1e22};

    private NumberParse() {
    }

    public static long parseLong(CharSequence text, long fallback) {
        return text == null ? fallback : parseLong(text, 0, text.length(), fallback);
    }

    /**
     * The figure in {@code [start, end)} as a whole number. A fraction is kept only through a
     * suffix ({@code 1.5K} is 1500) and otherwise truncated toward zero, as a {@code (long)} cast of
     * {@link #parseDouble} would.
     */
    public static long parseLong(CharSequence text, int start, int end, long fallback) {
        if (text == null) {
            return fallback;
        }
        while (start < end && text.charAt(start) == ' ') {
            start++;
        }
        while (end > start && text.charAt(end - 1) == ' ') {
            end--;
        }
        if (start >= end) {
            return fallback;
        }
        long scale = scaleOf(text.charAt(end - 1));
        if (scale == 1) {
            // Integer fast path: exact, no double rounding for large values.
            boolean negative = text.charAt(start) == '-';
            int i = negative ? start + 1 : start;
            long value = 0;
            boolean digits = false;
            for (; i < end; i++) {
                char c = text.charAt(i);
                if (c >= '0' && c <= '9') {
                    if (value > (Long.MAX_VALUE - (c - '0')) / 10) {
                        return fallback;
                    }
                    value = value * 10 + (c - '0');
                    digits = true;
                } else if (c != ',') {
                    break;
                }
            }
            if (i == end) {
                return digits ? (negative ? -value : value) : fallback;
            }
            if (text.charAt(i) != '.') {
                return fallback;
            }
        }
        double value = parseDouble(text, start, end, Double.NaN);
        return Double.isNaN(value) ? fallback : (long) value;
    }

    public static double parseDouble(CharSequence text, double fallback) {
        return text == null ? fallback : parseDouble(text, 0, text.length(), fallback);
    }

    /** The figure in {@code [start, end)}, suffix applied. */
    public static double parseDouble(CharSequence text, int start, int end, double fallback) {
        if (text == null) {
            return fallback;
        }
        while (start < end && text.charAt(start) == ' ') {
            start++;
        }
        while (end > start && text.charAt(end - 1) == ' ') {
            end--;
        }
        if (start >= end) {
            return fallback;
        }
        long scale = scaleOf(text.charAt(end - 1));
        if (scale != 1) {
            end--;
        }
        boolean negative = start < end && text.charAt(start) == '-';
        int i = negative ? start + 1 : start;
        long mantissa = 0;
        int fractionDigits = 0;
        boolean digits = false;
        boolean point = false;
        for (; i < end; i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                mantissa = mantissa * 10 + (c - '0');
                digits = true;
                if (point) {
                    fractionDigits++;
                }
                if (mantissa >= EXACT_MANTISSA || fractionDigits >= POW10.length) {
                    return exactSlow(text, start, end, scale, fallback);
                }
            } else if (c == ',') {
                // grouping - skipped wherever it falls, as replace(",", "") did
            } else if (c == '.' && !point) {
                point = true;
            } else {
                return fallback;
            }
        }
        if (!digits) {
            return fallback;
        }
        // mantissa < 2^53 and 10^n <= 10^22 are both exact, so this division is correctly rounded -
        // the same double Double.parseDouble produces for the same digits.
        double value = fractionDigits == 0 ? mantissa : mantissa / POW10[fractionDigits];
        value *= scale;
        return negative ? -value : value;
    }

    /**
     * Only the digits in {@code [start, end)}, everything else ignored - what
     * {@code Long.parseLong(s.replaceAll("[^0-9]", ""))} returned, without the copy. No digits, or
     * more than a long holds, gives {@code fallback}.
     */
    public static long parseDigits(CharSequence text, int start, int end, long fallback) {
        if (text == null) {
            return fallback;
        }
        long value = 0;
        boolean digits = false;
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                if (value > (Long.MAX_VALUE - (c - '0')) / 10) {
                    return fallback;
                }
                value = value * 10 + (c - '0');
                digits = true;
            }
        }
        return digits ? value : fallback;
    }

    public static long parseDigits(CharSequence text, long fallback) {
        return text == null ? fallback : parseDigits(text, 0, text.length(), fallback);
    }

    private static long scaleOf(char c) {
        return switch (c) {
            case 'k', 'K' -> 1_000L;
            case 'm', 'M' -> 1_000_000L;
            case 'b', 'B' -> 1_000_000_000L;
            default -> 1L;
        };
    }

    /** More digits than a double holds exactly: rare enough that the copy does not matter. */
    private static double exactSlow(CharSequence text, int start, int end, long scale, double fallback) {
        StringBuilder sb = new StringBuilder(end - start);
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            if (c != ',') {
                sb.append(c);
            }
        }
        try {
            return Double.parseDouble(sb.toString()) * scale;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
