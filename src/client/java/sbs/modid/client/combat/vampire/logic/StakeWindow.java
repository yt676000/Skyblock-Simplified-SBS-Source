/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.vampire.logic;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether your Riftstalker Bloodfiend can be finished with the Steak Stake: its health, read off
 * its own nametag, at or below a share of its maximum (20 % by default).
 *
 * <p><b>Where the maximum comes from.</b> A tag that shows {@code current/max❤} is taken at its
 * word. A tag that shows only the current hearts is measured against the highest value seen this
 * fight - the boss spawns at full health, so the first reading is the maximum. Joining a fight
 * half-way makes that peak too low, which can only make the call-out late, never early.
 *
 * <p>The 20 % figure is the wiki's; whether Hypixel also marks the window itself (a symbol, a
 * title, a sound) is not known yet. The {@code [SBS][Riftstalker]} probe in {@code SlayerTracker}
 * exists to find out, and a marker it turns up should replace the arithmetic here.
 *
 * <p>Pure: no Minecraft types, so the parse and the threshold are unit-tested.
 */
public final class StakeWindow {

    /**
     * Hearts on a Rift boss tag: {@code 1,234❤}, {@code 2.4k❤}, {@code 480/2.4k❤}. The last match on
     * the line is the boss's own - a name never ends in hearts, the health always does.
     */
    private static final Pattern HEARTS = Pattern.compile(
            "([0-9][0-9,]*(?:\\.[0-9]+)?)([kKmM]?)(?:\\s*/\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)([kKmM]?))?\\s*[❤♥]");

    /** Current and maximum hearts off one tag; {@code max} is {@code NaN} when the tag has none. */
    public record Hearts(double current, double max) {
    }

    private double peak;
    private boolean executable;

    /**
     * Reads the hearts off a colour-stripped boss tag, or {@code null} when it carries none (the
     * hit-counting phase, a tag mid-rename).
     */
    public static Hearts parse(String plainTag) {
        if (plainTag == null || plainTag.isEmpty()) {
            return null;
        }
        Matcher matcher = HEARTS.matcher(plainTag);
        Hearts last = null;
        while (matcher.find()) {
            double current = number(matcher.group(1), matcher.group(2));
            double max = matcher.group(3) == null ? Double.NaN : number(matcher.group(3), matcher.group(4));
            if (!Double.isNaN(current)) {
                last = new Hearts(current, max);
            }
        }
        return last;
    }

    /** Whether {@code current} hearts out of {@code max} is inside the stake window. */
    public static boolean inWindow(double current, double max, int thresholdPercent) {
        if (!(max > 0) || !(current > 0)) {
            return false;   // a dead boss is not a stake target; no maximum, no share
        }
        return current <= max * thresholdPercent / 100.0;
    }

    /**
     * Feeds this scan's tag of your own boss. Returns whether it is executable now; a tag without
     * hearts leaves the last answer standing rather than flickering the box off for one scan.
     */
    public boolean update(String plainTag, int thresholdPercent) {
        Hearts hearts = parse(plainTag);
        if (hearts == null) {
            return executable;
        }
        double max = hearts.max();
        if (Double.isNaN(max)) {
            peak = Math.max(peak, hearts.current());
            max = peak;
        }
        executable = inWindow(hearts.current(), max, thresholdPercent);
        return executable;
    }

    public boolean executable() {
        return executable;
    }

    /** A new boss, or none: forget the peak and the window. */
    public void reset() {
        peak = 0;
        executable = false;
    }

    private static double number(String digits, String suffix) {
        try {
            double value = Double.parseDouble(digits.replace(",", ""));
            return switch (suffix.toLowerCase(Locale.ROOT)) {
                case "k" -> value * 1_000;
                case "m" -> value * 1_000_000;
                default -> value;
            };
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
