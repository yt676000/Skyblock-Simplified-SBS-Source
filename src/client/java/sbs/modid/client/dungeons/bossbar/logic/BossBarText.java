/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.bossbar.logic;

import java.util.Locale;
import java.util.function.DoubleFunction;

/**
 * The pure half of Boss Bar Health: which boss a bar names, what a boss nametag says its health is,
 * and the text appended to the bar. No Minecraft types, so every rule here is unit-tested.
 *
 * <p>Runs on the client tick and only when a bar's name, progress, nametag or a setting changed -
 * never per frame (core AGENTS, rule 3).
 */
public final class BossBarText {

    /** Health marks in a nametag, as Hypixel prints them. */
    private static final String HEALTH_MARKS = "❤♥";

    /** Where the bar's health came from - decides the prefix and how much is shown. */
    public enum Source {
        /** Read off the boss's own nametag. Exact. */
        NAMETAG,
        /** The bar's progress times a tabled maximum. Shown with "≈". */
        ESTIMATE,
        /** No maximum known: the bar's progress as a percentage, nothing invented. */
        PERCENT
    }

    /**
     * The Catacombs bosses. {@code keyword} is the lower-case word searched for in the bar name and
     * in nametags; it is matched as a whole word, so "Storm" never matches "Stormy".
     */
    public enum Boss {
        BONZO("bonzo"),
        SCARF("scarf"),
        PROFESSOR("professor"),
        THORN("thorn"),
        LIVID("livid"),
        SADAN("sadan"),
        MAXOR("maxor"),
        STORM("storm"),
        GOLDOR("goldor"),
        NECRON("necron");

        private final String keyword;

        Boss(String keyword) {
            this.keyword = keyword;
        }

        public String keyword() {
            return keyword;
        }
    }

    /** A health reading: {@code max} is {@code -1} when the source does not state one. */
    public record Hp(double current, double max) {
    }

    private BossBarText() {
    }

    /** Removes {@code §x} formatting codes. */
    public static String strip(String raw) {
        if (raw == null || raw.indexOf('§') < 0) {
            return raw == null ? "" : raw;
        }
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '§') {
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /** The boss a bar (or nametag) names, or {@code null}. Colour codes and decoration are ignored. */
    public static Boss identify(String raw) {
        String lower = strip(raw).toLowerCase(Locale.ROOT);
        for (Boss boss : Boss.values()) {
            if (containsWord(lower, boss.keyword)) {
                return boss;
            }
        }
        return null;
    }

    /** Whether {@code lower} contains {@code word} with no letter directly on either side. */
    public static boolean containsWord(String lower, String word) {
        int from = 0;
        while (true) {
            int at = lower.indexOf(word, from);
            if (at < 0) {
                return false;
            }
            int end = at + word.length();
            boolean left = at == 0 || !Character.isLetter(lower.charAt(at - 1));
            boolean right = end >= lower.length() || !Character.isLetter(lower.charAt(end));
            if (left && right) {
                return true;
            }
            from = at + 1;
        }
    }

    /**
     * The health in a nametag: {@code "... 412.5M/800M❤"} or the bare {@code "412.5M❤"}. Taken as
     * the run of digits, separators and scale letters directly in front of the last heart, so the
     * level prefix and colouring around it do not matter. {@code null} when there is no readout.
     */
    public static Hp parseNametag(String raw) {
        String clean = strip(raw);
        int heart = -1;
        for (int i = clean.length() - 1; i >= 0; i--) {
            if (HEALTH_MARKS.indexOf(clean.charAt(i)) >= 0) {
                heart = i;
                break;
            }
        }
        if (heart <= 0) {
            return null;
        }
        int start = heart;
        while (start > 0 && isHealthChar(clean.charAt(start - 1))) {
            start--;
        }
        String health = clean.substring(start, heart).trim();
        if (health.isEmpty()) {
            return null;
        }
        int slash = health.indexOf('/');
        double current = amount(slash < 0 ? health : health.substring(0, slash));
        if (current < 0) {
            return null;
        }
        double max = slash < 0 ? -1 : amount(health.substring(slash + 1));
        return new Hp(current, max > 0 ? max : -1);
    }

    private static boolean isHealthChar(char c) {
        return Character.isDigit(c) || c == '.' || c == ',' || c == '/' || "kKmMbB".indexOf(c) >= 0;
    }

    /** {@code "412.5M"} to {@code 412_500_000}; {@code -1} when it is not a number. */
    public static double amount(String text) {
        String value = text.trim().replace(",", "");
        if (value.isEmpty()) {
            return -1;
        }
        double scale = switch (Character.toLowerCase(value.charAt(value.length() - 1))) {
            case 'k' -> 1_000d;
            case 'm' -> 1_000_000d;
            case 'b' -> 1_000_000_000d;
            default -> 1d;
        };
        if (scale > 1) {
            value = value.substring(0, value.length() - 1);
        }
        try {
            double parsed = Double.parseDouble(value) * scale;
            return parsed >= 0 ? parsed : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The bar's progress times the tabled maximum: an estimate, clamped to {@code [0, max]}. */
    public static Hp estimate(float progress, double max) {
        double p = Math.max(0, Math.min(1, progress));
        return new Hp(p * max, max);
    }

    /** Remaining fraction in {@code [0, 1]}: from the reading when it has a maximum, else the bar. */
    public static double fraction(Hp hp, float progress) {
        if (hp != null && hp.max() > 0) {
            return Math.max(0, Math.min(1, hp.current() / hp.max()));
        }
        return Math.max(0, Math.min(1, progress));
    }

    /**
     * The text appended after the bar's own name, without colour: {@code " — 412.5M / 800M ❤ (51%)"},
     * {@code " — ≈412.5M / 800M ❤ (51%)"} for an estimate, {@code " — 51%"} with no maximum.
     *
     * @param hp      the reading, or {@code null} for {@link Source#PERCENT}
     * @param numbers formats a health number (the mod-wide k/m/b setting in game)
     */
    public static String suffix(Source source, Hp hp, float progress, boolean showMax, boolean showPercent,
                                DoubleFunction<String> numbers) {
        int percent = (int) Math.round(fraction(hp, progress) * 100);
        if (source == Source.PERCENT || hp == null) {
            return " — " + percent + "%";
        }
        StringBuilder out = new StringBuilder(32).append(" — ");
        if (source == Source.ESTIMATE) {
            out.append('≈');
        }
        out.append(numbers.apply(hp.current()));
        if (showMax && hp.max() > 0) {
            out.append(" / ").append(numbers.apply(hp.max()));
        }
        out.append(" ❤");
        if (showPercent) {
            out.append(" (").append(percent).append("%)");
        }
        return out.toString();
    }

    /** Legacy colour for the remaining fraction: green over half, yellow over a quarter, else red. */
    public static char colourCode(double fraction) {
        return fraction > 0.5 ? 'a' : fraction > 0.25 ? 'e' : 'c';
    }
}
