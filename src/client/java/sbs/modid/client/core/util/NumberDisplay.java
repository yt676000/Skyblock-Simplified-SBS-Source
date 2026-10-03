/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.util;

import sbs.modid.client.core.config.ConfigManager;

import java.util.Locale;

/**
 * The one place SBS turns a big number into text - so "Shorten Numbers"
 * ({@code convenience.shortenNumbers}) is a single switch for the whole mod instead of a per-HUD
 * habit.
 *
 * <p>On, every large value reads {@code 1.4K} / {@code 12.7M} / {@code 3.2B}; off, it reads
 * {@code 1,400} / {@code 12,700,000} / {@code 3,200,000,000}. Both directions matter: the HUD cards
 * were already compact and the price tooltips were already grouped, and neither had a way for the
 * player to say which they wanted.
 *
 * <p>The suffixes are <b>upper case</b> because that is how Hypixel itself writes them, and a number
 * the game and the mod spell differently reads as two different numbers at a glance.
 *
 * <p>Trillions get a {@code T} as well. Nobody asked for it, but a bank balance past 1e12 would
 * otherwise read "1234B", which is worse than either option.
 *
 * <p>This is for values the player <b>reads</b>. Anything parsed back, exported to a file or sent to
 * a server keeps its own exact formatting - {@link MathEval} (a calculator) deliberately does not go
 * through here.
 */
public final class NumberDisplay {

    private static final long[] STEP = {1_000_000_000_000L, 1_000_000_000L, 1_000_000L, 1_000L};
    private static final String[] SUFFIX = {"T", "B", "M", "K"};

    private NumberDisplay() {
    }

    /** Whether the player asked for shortened numbers (defaults to yes if the config is not up yet). */
    public static boolean enabled() {
        try {
            return ConfigManager.getInstance().get().convenience.shortenNumbers;
        } catch (Exception configNotReady) {
            return true;
        }
    }

    /** A number the way the player has asked to see it: {@code 12.7M} or {@code 12,700,000}. */
    public static String format(double value) {
        return enabled() ? shorten(value) : grouped(value);
    }

    /** A number the way the player has asked to see it: {@code 12.7M} or {@code 12,700,000}. */
    public static String format(long value) {
        return enabled() ? shorten(value) : grouped(value);
    }

    /**
     * Always the short form, whatever the setting says - for the few places where the full number
     * cannot fit no matter what the player prefers.
     */
    public static String shorten(double value) {
        double abs = Math.abs(value);
        for (int i = 0; i < STEP.length; i++) {
            // 0.9995, not 1: 999,999 rounds to 1000.0 at one decimal, and "1000K" is the one output
            // this must never produce. Taking the step early makes it "1M" instead.
            if (abs >= STEP[i] * 0.9995d) {
                return trim(value / STEP[i]) + SUFFIX[i];
            }
        }
        return grouped(value);
    }

    /** Always the full number with thousands separators ({@code 12,700,000}). */
    public static String grouped(double value) {
        return String.format(Locale.ROOT, "%,d", Math.round(value));
    }

    /**
     * The scaled figure at about three digits: no decimal once it reaches 100, one below that, and
     * never a trailing {@code .0}. So 613,283,830 is {@code 613M} and 1,613,283,830 is {@code 1.6B}.
     *
     * <p>A decimal only earns its place while the whole number is short. "613.3M" spends a character
     * on a tenth of a million - a precision nobody reads off a HUD - where "1.6B" without it would
     * lose the difference between one and two billion.
     */
    private static String trim(double value) {
        if (Math.abs(value) >= 100d) {
            return String.valueOf(Math.round(value));
        }
        String text = String.format(Locale.ROOT, "%.1f", value);
        return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
    }
}
