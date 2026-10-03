/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.essenceshop.logic;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The part of the essence shop that reads Hypixel's own words: a perk's current level, and the
 * player's essence, out of colour-stripped menu text.
 *
 * <p><b>This is the feature's weak point and it is deliberately separate.</b> The exact wording of
 * the level line, the maxed marker and the balance line has not been read off a live client, so
 * every pattern here is written to be tolerant and to fail to "not known" rather than to a number.
 * Being pure functions over plain strings, they are also the part that can be pinned by tests -
 * which is what stops a later tweak for one wording from silently breaking another.
 *
 * <p>When a wording turns out to be different, this class is the only thing that needs changing.
 * {@code /sbs probe} in an open shop captures what to change it to.
 */
public final class PerkLevelReader {

    /** "3/5" - only trusted when the second number is the perk's real maximum. */
    private static final Pattern LEVEL_SLASH = Pattern.compile("(\\d{1,3})\\s*/\\s*(\\d{1,3})");

    /** "Level 3", "Tier 3", "Level: 3". */
    private static final Pattern LEVEL_WORD =
            Pattern.compile("\\b(?:level|lvl|tier)\\b\\s*:?\\s*(\\d{1,3})", Pattern.CASE_INSENSITIVE);

    /**
     * The player's own essence, which only counts with a word saying it is theirs. Without that
     * qualifier this would happily read a perk's price ("250 Wither Essence") as a balance, and a
     * shortfall computed from a price is worse than no shortfall at all.
     */
    private static final Pattern BALANCE = Pattern.compile(
            "(?:you have|you own|your balance|balance|owned)\\D{0,24}?([\\d,]+)",
            Pattern.CASE_INSENSITIVE);

    /** A trailing level on a perk name: "Forbidden Strength III" / "Forbidden Strength 3". */
    private static final Pattern NAME_LEVEL = Pattern.compile("\\s+([IVXL]{1,6}|\\d{1,3})$");

    private PerkLevelReader() {
    }

    /**
     * The level stated by a perk's own lore, or {@code -1} when nothing states one.
     *
     * <p>Three passes, strongest reading first, because the same digit can appear for several
     * reasons in one tooltip: an explicit "3/5" whose maximum matches the table is unambiguous, a
     * standalone maxed marker is unambiguous, and a bare "Level 3" is read only once neither
     * applies - and never off a line mentioning a maximum, where the number is the cap rather than
     * where the player is.
     */
    public static int fromLore(List<String> lore, int maxLevel) {
        if (lore == null) {
            return -1;
        }
        for (String line : lore) {
            Matcher matcher = LEVEL_SLASH.matcher(line);
            while (matcher.find()) {
                int current = parseInt(matcher.group(1));
                int max = parseInt(matcher.group(2));
                if (max == maxLevel && current >= 0 && current <= max) {
                    return current;
                }
            }
        }
        for (String line : lore) {
            if (isMaxedMarker(line)) {
                return maxLevel;
            }
        }
        for (String line : lore) {
            if (line.toLowerCase(Locale.ROOT).contains("max")) {
                continue;   // "Max Level: 5" states the cap, not the player's level
            }
            Matcher matcher = LEVEL_WORD.matcher(line);
            if (matcher.find()) {
                int level = parseInt(matcher.group(1));
                if (level >= 0 && level <= maxLevel) {
                    return level;
                }
            }
        }
        return -1;
    }

    /**
     * A line that says nothing except that the perk is finished ("MAXED", "MAX LEVEL").
     *
     * <p>A digit anywhere disqualifies it, and that guard is the whole reason this is a method
     * rather than a contains-check: without it "Max Level: 5" reduces to the same letters as
     * "MAX LEVEL" and reads as a finished perk, which silently removes its entire cost from the
     * total. The input is expected colour-stripped, as everything the shop reader passes on is.
     */
    public static boolean isMaxedMarker(String line) {
        if (line == null) {
            return false;
        }
        for (int i = 0; i < line.length(); i++) {
            if (Character.isDigit(line.charAt(i))) {
                return false;
            }
        }
        StringBuilder letters = new StringBuilder();
        for (char c : line.toCharArray()) {
            if (Character.isLetter(c)) {
                letters.append(Character.toLowerCase(c));
            } else if (letters.length() > 0 && letters.charAt(letters.length() - 1) != ' ') {
                letters.append(' ');
            }
        }
        String bare = letters.toString().trim();
        return bare.equals("maxed") || bare.equals("maxed out") || bare.equals("max level")
                || bare.equals("maxed level") || bare.equals("max");
    }

    /** The player's essence as the menu states it, or {@code -1} when it does not state it. */
    public static long balanceFrom(List<String> lore) {
        if (lore == null) {
            return -1;
        }
        for (String line : lore) {
            if (!line.toLowerCase(Locale.ROOT).contains("essence")) {
                continue;
            }
            Matcher matcher = BALANCE.matcher(line);
            if (matcher.find()) {
                try {
                    return Long.parseLong(matcher.group(1).replace(",", ""));
                } catch (NumberFormatException notANumber) {
                    return -1;
                }
            }
        }
        return -1;
    }

    /**
     * A perk name split into the part the cost table stores and the level written after it, or
     * {@code null} when the name carries no trailing level.
     *
     * @param base  the name without its level suffix
     * @param level the level that suffix names, always {@code > 0}
     */
    public record NamedLevel(String base, int level) {
    }

    /** Splits "Forbidden Strength III" into its name and its 3, or {@code null} when there is none. */
    public static NamedLevel splitTrailingLevel(String name) {
        if (name == null) {
            return null;
        }
        Matcher matcher = NAME_LEVEL.matcher(name);
        if (!matcher.find()) {
            return null;
        }
        int level = parseLevelToken(matcher.group(1));
        return level > 0 ? new NamedLevel(name.substring(0, matcher.start()), level) : null;
    }

    /** A roman ({@code III}) or arabic ({@code 3}) level token, or {@code 0} when it is neither. */
    public static int parseLevelToken(String token) {
        if (token == null || token.isEmpty()) {
            return 0;
        }
        if (Character.isDigit(token.charAt(0))) {
            return Math.max(0, parseInt(token));
        }
        int total = 0;
        int highest = 0;
        for (int i = token.length() - 1; i >= 0; i--) {
            int value = switch (Character.toUpperCase(token.charAt(i))) {
                case 'I' -> 1;
                case 'V' -> 5;
                case 'X' -> 10;
                case 'L' -> 50;
                default -> 0;
            };
            if (value == 0) {
                return 0;
            }
            total += value < highest ? -value : value;
            highest = Math.max(highest, value);
        }
        return Math.max(0, total);
    }

    private static int parseInt(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException notANumber) {
            return -1;
        }
    }
}
