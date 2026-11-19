/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.localflips.logic;

import java.util.Locale;

/**
 * Number formatting for the Local Flip Finder: coins, percentages, durations.
 *
 * <p>Deliberately not {@code NumberDisplay}: that one follows the player's "Shorten Numbers"
 * setting, and this window is a trading tool whose numbers are compared at a glance across cards -
 * they keep one fixed short form ({@code 7.00M}, {@code 624k}, {@code 1.2B}) whatever that says.
 * Every method is pure and allocation-light; callers build the strings when their model changes,
 * never per frame.
 */
public final class FlipText {

    private FlipText() {
    }

    /**
     * Short coins: below 1 000 as is; {@code 1.5k} up to 10 000, {@code 624k} up to a million;
     * millions always with two decimals ({@code 7.00M}, {@code 117.88M}); billions with up to two,
     * trailing zeros dropped ({@code 1.2B}). A value that would round up into the next unit is shown
     * in that unit ({@code 999 999 -> 1.00M}, not {@code 1000k}).
     */
    public static String coins(long value) {
        if (value == Long.MIN_VALUE) {
            return "?";
        }
        if (value < 0) {
            return "-" + coins(-value);
        }
        if (value < 1_000) {
            return Long.toString(value);
        }
        if (value < 10_000) {
            // 9 950 rounds to 10.0k - fall through to the whole-thousands form for those.
            double k = Math.round(value / 100.0) / 10.0;
            if (k < 10) {
                return trimmed(k, 1) + "k";
            }
        }
        long thousands = Math.round(value / 1_000.0);
        if (thousands < 1_000) {
            return thousands + "k";
        }
        double millions = Math.round(value / 10_000.0) / 100.0;
        if (millions < 1_000) {
            return String.format(Locale.ROOT, "%.2fM", millions);
        }
        double billions = Math.round(value / 10_000_000.0) / 100.0;
        return trimmed(billions, 2) + "B";
    }

    /** {@code 26} -> {@code "26 %"}, {@code 36.4} -> {@code "36.4 %"}; {@code "?"} for NaN. */
    public static String pct(double value) {
        if (Double.isNaN(value)) {
            return "?";
        }
        return trimmed(Math.round(value * 10) / 10.0, 1) + " %";
    }

    /** Hours as given by the program: {@code 1} -> {@code "1 h"}, {@code 2.3} -> {@code "2.3 h"}. */
    public static String hours(double value) {
        if (Double.isNaN(value)) {
            return "?";
        }
        if (value < 1) {
            return Math.max(1, Math.round(value * 60)) + " min";
        }
        return trimmed(Math.round(value * 10) / 10.0, 1) + " h";
    }

    /** Minutes with one decimal below ten, whole above: {@code 11.1} -> {@code "11 min"}. */
    public static String minutes(double value) {
        if (Double.isNaN(value)) {
            return "?";
        }
        if (value < 10) {
            return trimmed(Math.round(value * 10) / 10.0, 1) + " min";
        }
        return Math.round(value) + " min";
    }

    /** Elapsed seconds as {@code 12 s}, {@code 3 min}, {@code 2 h}. Negative (clock skew) reads 0 s. */
    public static String ago(long seconds) {
        long s = Math.max(0, seconds);
        if (s < 60) {
            return s + " s";
        }
        if (s < 3_600) {
            return (s / 60) + " min";
        }
        return (s / 3_600) + " h";
    }

    /**
     * Reads a typed amount: {@code 500k}, {@code 1.5m}, {@code 2b}, {@code 750000}; a comma counts
     * as the decimal point. Blank or unreadable is 0 (no filter).
     */
    public static long parseCoins(String text) {
        String t = text == null ? "" : text.trim().toLowerCase(Locale.ROOT).replace(',', '.').replace(" ", "");
        if (t.isEmpty()) {
            return 0;
        }
        double mult = 1;
        char last = t.charAt(t.length() - 1);
        if (last == 'k' || last == 'm' || last == 'b') {
            mult = last == 'k' ? 1e3 : last == 'm' ? 1e6 : 1e9;
            t = t.substring(0, t.length() - 1);
        }
        try {
            double value = Double.parseDouble(t) * mult;
            return Double.isNaN(value) || value <= 0 ? 0 : (long) Math.min(Long.MAX_VALUE / 2, Math.round(value));
        } catch (NumberFormatException unreadable) {
            return 0;
        }
    }

    /** One decimal, trailing zero dropped: {@code 17.2}, {@code 136.4}, {@code 5}. */
    public static String decimal1(double value) {
        return Double.isNaN(value) ? "?" : trimmed(Math.round(value * 10) / 10.0, 1);
    }

    /** {@code 1.50} with {@code decimals = 2} -> {@code "1.5"}; {@code 7.0} -> {@code "7"}. */
    static String trimmed(double value, int decimals) {
        String text = String.format(Locale.ROOT, "%." + decimals + "f", value);
        if (text.indexOf('.') >= 0) {
            int end = text.length();
            while (text.charAt(end - 1) == '0') {
                end--;
            }
            if (text.charAt(end - 1) == '.') {
                end--;
            }
            text = text.substring(0, end);
        }
        return text;
    }
}
