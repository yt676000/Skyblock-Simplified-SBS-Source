/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.inventory.model;

import sbs.modid.client.helper.rift.model.Certainty;

import java.util.Locale;

/**
 * How much Magical Power one accessory is worth, by rarity.
 *
 * <p>Hypixel publishes an accessory's rarity but never its Magical Power, so this table is the one
 * piece of the missing-accessory feature that is <b>not</b> derived from the item resource. It is
 * therefore carried at {@link Certainty#WIKI} and the UI says so: the headline "you would gain 16
 * MP" is a prediction, and a player who trusts a stale number more than the game's own display is
 * exactly the failure this project keeps guarding against.
 *
 * <p>Two rarities deliberately return {@link Certainty#UNKNOWN} rather than a number:
 * <ul>
 *   <li>{@code SUPREME} - one accessory carries it (the Celestial Starstone) and its contribution
 *       has never been checked here;</li>
 *   <li>no rarity at all - 38 catalogue entries have no {@code tier} in Hypixel's resource, and
 *       guessing one would manufacture a number out of a missing field.</li>
 * </ul>
 * Both are shown as {@code ?} and excluded from the totals, which is the honest answer.
 *
 * <p><b>Promote one at a time.</b> Watch the Accessory Bag's own Magical Power figure move by the
 * expected amount when a piece of that rarity is added, then move that rarity - and only that one -
 * to {@link Certainty#CONFIRMED}.
 */
public final class MagicalPower {

    /** The Magical Power a single accessory of this rarity contributes, and how sure we are. */
    public record Value(int power, Certainty certainty) {

        /** Whether this carries a usable number at all. */
        public boolean known() {
            return certainty != Certainty.UNKNOWN;
        }

        /** The number for display, or {@code ?} when there is none. */
        public String display() {
            return known() ? String.valueOf(power) : "?";
        }
    }

    private static final Value UNKNOWN = new Value(0, Certainty.UNKNOWN);

    private MagicalPower() {
    }

    /**
     * The Magical Power for a rarity name as Hypixel spells it ({@code "LEGENDARY"}).
     *
     * <p>An unrecognised or blank rarity returns {@link Certainty#UNKNOWN} rather than zero, so a
     * rarity Hypixel adds tomorrow shows up as an unanswered question instead of silently dragging
     * every total down.
     */
    public static Value of(String tier) {
        if (tier == null || tier.isBlank()) {
            return UNKNOWN;
        }
        return switch (tier.toUpperCase(Locale.ROOT)) {
            case "COMMON" -> new Value(3, Certainty.WIKI);
            case "UNCOMMON" -> new Value(5, Certainty.WIKI);
            case "RARE" -> new Value(8, Certainty.WIKI);
            case "EPIC" -> new Value(12, Certainty.WIKI);
            case "LEGENDARY" -> new Value(16, Certainty.WIKI);
            case "MYTHIC" -> new Value(22, Certainty.WIKI);
            case "SPECIAL" -> new Value(3, Certainty.WIKI);
            case "VERY_SPECIAL" -> new Value(5, Certainty.WIKI);
            default -> UNKNOWN;
        };
    }

    /**
     * The rarity one step up - what a recombobulator does to an accessory, and therefore what
     * decides its Magical Power once one has been used on it.
     *
     * <p>A rarity with no defined step up (the top of the ladder, or one this build does not know)
     * is returned unchanged, so an unrecognised rarity is never invented into a different one.
     */
    public static String upgraded(String tier) {
        if (tier == null || tier.isBlank()) {
            return tier;
        }
        return switch (tier.toUpperCase(Locale.ROOT)) {
            case "COMMON" -> "UNCOMMON";
            case "UNCOMMON" -> "RARE";
            case "RARE" -> "EPIC";
            case "EPIC" -> "LEGENDARY";
            case "LEGENDARY" -> "MYTHIC";
            default -> tier;
        };
    }

    /** The legacy colour code Hypixel draws this rarity in, for name and border colouring. */
    public static String colorCode(String tier) {
        if (tier == null) {
            return "§f";
        }
        return switch (tier.toUpperCase(Locale.ROOT)) {
            case "UNCOMMON" -> "§a";
            case "RARE" -> "§9";
            case "EPIC" -> "§5";
            case "LEGENDARY" -> "§6";
            case "MYTHIC" -> "§d";
            case "SUPREME", "DIVINE" -> "§b";
            case "SPECIAL", "VERY_SPECIAL" -> "§c";
            default -> "§f";
        };
    }

    /** ARGB for the same rarity, for the cell borders. Mirrors {@link #colorCode(String)}. */
    public static int argb(String tier) {
        if (tier == null) {
            return 0xFFFFFFFF;
        }
        return switch (tier.toUpperCase(Locale.ROOT)) {
            case "UNCOMMON" -> 0xFF55FF55;
            case "RARE" -> 0xFF5555FF;
            case "EPIC" -> 0xFFAA00AA;
            case "LEGENDARY" -> 0xFFFFAA00;
            case "MYTHIC" -> 0xFFFF55FF;
            case "SUPREME", "DIVINE" -> 0xFF55FFFF;
            case "SPECIAL", "VERY_SPECIAL" -> 0xFFFF5555;
            default -> 0xFFAAAAAA;
        };
    }

    /**
     * Sort weight for a rarity, ascending. Unknown rarities sort below {@code COMMON} rather than
     * above {@code MYTHIC} - an unranked entry belongs at the quiet end of the list.
     */
    public static int rank(String tier) {
        if (tier == null || tier.isBlank()) {
            return -1;
        }
        return switch (tier.toUpperCase(Locale.ROOT)) {
            case "COMMON" -> 0;
            case "UNCOMMON" -> 1;
            case "RARE" -> 2;
            case "EPIC" -> 3;
            case "LEGENDARY" -> 4;
            case "MYTHIC" -> 5;
            case "SUPREME" -> 6;
            case "DIVINE" -> 7;
            case "SPECIAL" -> 8;
            case "VERY_SPECIAL" -> 9;
            default -> -1;
        };
    }

    /** A rarity as Hypixel writes it, turned into a label ({@code VERY_SPECIAL} -> "Very Special"). */
    public static String pretty(String tier) {
        if (tier == null || tier.isBlank()) {
            return "Unknown";
        }
        StringBuilder out = new StringBuilder(tier.length());
        for (String word : tier.split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0)))
                    .append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return out.toString();
    }
}
