/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.experiment.logic;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure parser for the Experimentation Table's reward chat: the claim line and the reward lines
 * printed after it. No Minecraft types, so it is unit tested on the captured lines.
 *
 * <p>Line certainty (colour-stripped):
 * <ul>
 *   <li>{@code You claimed the Superpairs|Chronomatron|Ultrasequencer rewards!} - CONFIRMED.</li>
 *   <li>{@code   +Titanic Experience Bottle} (two leading spaces) - CONFIRMED.</li>
 *   <li>{@code [Bazaar] Bought 64x Titanic Experience Bottle for N coins!} - CONFIRMED.</li>
 *   <li>Book, XP and other reward lines - UNVERIFIED: accepted in the expected
 *       {@code   +<reward>} shape; the probe in {@link ExperimentProfit} logs what really arrives.</li>
 * </ul>
 */
public final class ExperimentRewardParser {

    /** Which game a claim line names. */
    public enum Game { SUPERPAIRS, CHRONOMATRON, ULTRASEQUENCER }

    /** What a reward line was read as. */
    public enum Kind {
        /** Enchanting experience: counted, never priced. */
        XP,
        /** An item with a market id ({@link Reward#itemId}). */
        ITEM,
        /** An enchanted book ({@link Reward#enchant} lower_snake display base, {@link Reward#tier}). */
        BOOK
    }

    /**
     * One reward line. {@code amount} is the item count, or the experience for {@link Kind#XP};
     * {@code name} is the line's text without the {@code +} and the count.
     */
    public record Reward(Kind kind, String name, long amount, String itemId, String enchant, int tier) {
    }

    private static final Pattern FORMATTING = Pattern.compile("§.");
    private static final Pattern CLAIM =
            Pattern.compile("^You claimed the (Superpairs|Chronomatron|Ultrasequencer) rewards!$");
    /** Leading whitespace is required: Hypixel indents reward lists, and it keeps "+N" chat out. */
    private static final Pattern REWARD = Pattern.compile("^\\s+\\+\\s*(?:([0-9][0-9,]*)x\\s+)?(.+?)\\s*$");
    /** {@code 143k Enchanting Exp}, {@code 30,000 Enchanting Experience}. */
    private static final Pattern XP = Pattern.compile(
            "^([0-9][0-9,]*(?:\\.[0-9]+)?)([kKmM]?)\\s+(?:Enchanting\\s+)?(?:Exp|Experience|XP)$");
    /** {@code Enchanted Book (Sharpness VI)} and the bare {@code Sharpness VI}. */
    private static final Pattern BOOK_WRAPPED = Pattern.compile("^Enchanted Book \\((.+)\\)$");
    private static final Pattern ENCHANT = Pattern.compile("^([A-Za-z' -]+?)\\s+([IVX]+|[0-9]{1,2})$");
    /** {@code [Bazaar] Bought 64x Titanic Experience Bottle for 1,234,567 coins!} */
    private static final Pattern BAZAAR_BOTTLE = Pattern.compile(
            "^\\[Bazaar] Bought ([0-9][0-9,]*)x (.*Experience Bottle) for ([0-9][0-9,.]*) coins!$");
    private static final Pattern NON_KEY = Pattern.compile("[^A-Z0-9]+");
    private static final Pattern EDGE = Pattern.compile("^_+|_+$");

    /** Bottle display names to their market ids (ids from the bundled item data). */
    private static final Map<String, String> BOTTLES = Map.of(
            "Experience Bottle", "EXP_BOTTLE",
            "Grand Experience Bottle", "GRAND_EXP_BOTTLE",
            "Titanic Experience Bottle", "TITANIC_EXP_BOTTLE",
            "Colossal Experience Bottle", "COLOSSAL_EXP_BOTTLE");

    private static final Map<String, Integer> ROMAN = Map.of(
            "I", 1, "II", 2, "III", 3, "IV", 4, "V", 5, "VI", 6, "VII", 7, "VIII", 8, "IX", 9, "X", 10);

    /** A bazaar purchase of experience bottles: count, bottle id, total coins. */
    public record BottlePurchase(int count, String itemId, long coins) {
    }

    private ExperimentRewardParser() {
    }

    public static String clean(String line) {
        return line == null ? "" : FORMATTING.matcher(line).replaceAll("");
    }

    /** The game a claim line names, or {@code null} when it is not a claim line. */
    public static Game claim(String clean) {
        Matcher m = CLAIM.matcher(clean.trim());
        return m.matches() ? Game.valueOf(m.group(1).toUpperCase(Locale.ROOT)) : null;
    }

    /** A reward line read, or {@code null} when the line is not in the indented {@code +} shape. */
    public static Reward reward(String clean) {
        Matcher m = REWARD.matcher(clean);
        if (!m.matches()) {
            return null;
        }
        long count = m.group(1) == null ? 1L : parseLong(m.group(1));
        String name = m.group(2);
        Matcher xp = XP.matcher(name);
        if (xp.matches()) {
            return new Reward(Kind.XP, name, experience(xp.group(1), xp.group(2)), null, null, 0);
        }
        String bottle = BOTTLES.get(name);
        if (bottle != null) {
            return new Reward(Kind.ITEM, name, count, bottle, null, 0);
        }
        Matcher wrapped = BOOK_WRAPPED.matcher(name);
        Matcher enchant = ENCHANT.matcher(wrapped.matches() ? wrapped.group(1) : name);
        if (enchant.matches()) {
            Integer tier = tier(enchant.group(2));
            if (tier != null) {
                String base = enchant.group(1).trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z]+", "_");
                return new Reward(Kind.BOOK, name, count, null, base, tier);
            }
        }
        return new Reward(Kind.ITEM, name, count, nameToId(name), null, 0);
    }

    /** A Bazaar experience-bottle purchase, or {@code null}. Probe input only, never a cost by itself. */
    public static BottlePurchase bottlePurchase(String clean) {
        Matcher m = BAZAAR_BOTTLE.matcher(clean.trim());
        if (!m.matches()) {
            return null;
        }
        String id = BOTTLES.get(m.group(2));
        if (id == null) {
            return null;
        }
        return new BottlePurchase((int) parseLong(m.group(1)), id,
                Math.round(Double.parseDouble(m.group(3).replace(",", ""))));
    }

    /** A display name in id convention: {@code "Jungle Key"} to {@code JUNGLE_KEY}. */
    static String nameToId(String name) {
        String upper = NON_KEY.matcher(name.toUpperCase(Locale.ROOT)).replaceAll("_");
        return EDGE.matcher(upper).replaceAll("");
    }

    private static Integer tier(String text) {
        if (text.chars().allMatch(Character::isDigit)) {
            int value = Integer.parseInt(text);
            return value >= 1 && value <= 10 ? value : null;
        }
        return ROMAN.get(text);
    }

    private static long experience(String number, String suffix) {
        double value = Double.parseDouble(number.replace(",", ""));
        return switch (suffix.toLowerCase(Locale.ROOT)) {
            case "k" -> Math.round(value * 1_000);
            case "m" -> Math.round(value * 1_000_000);
            default -> Math.round(value);
        };
    }

    private static long parseLong(String digits) {
        return Long.parseLong(digits.replace(",", ""));
    }
}
