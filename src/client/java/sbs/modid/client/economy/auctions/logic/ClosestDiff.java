/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.auctions.logic;

import java.util.Locale;
import java.util.Map;

/**
 * Naming for one {@code closest[].diff} entry ({@code {t, k, you, they, w}}), shared by every view
 * of the server's "closest" comparisons: the Similar Auctions window and the Lowball window. One
 * place decides what "ultimate_reiterate" or a gem slot is called, so the two never disagree.
 */
public final class ClosestDiff {

    /**
     * Ids Hypixel renamed in-game but kept in item NBT / the price API ("dragon_hunter" shows as
     * "Gravity"). Without this mapping players see enchants they have never heard of.
     */
    private static final Map<String, String> ID_RENAMES = Map.of("dragon_hunter", "Gravity");

    private ClosestDiff() {
    }

    /** The display label of a diff entry, or {@code null} when it has neither type nor key. */
    public static String label(String type, String k) {
        return switch (type == null ? "" : type) {
            // Enchant labels go through the shared actual-vs-shown mapping
            // ("ultimate_reiterate" -> "Duplex", "dragon_hunter" -> "Gravity").
            case "ench" -> k != null
                    ? sbs.modid.client.helper.enchants.EnchantNames.displayName(k) : pretty(type);
            case "attr", "rune", "scroll" -> k != null ? pretty(k) : pretty(type);
            case "gem", "gems" -> "Gem" + (k != null ? " " + pretty(k) : "");
            case "mod" -> k != null ? k : "Modifier";
            case "stars" -> "Stars";
            case "recomb" -> "Recombobulated";
            case "hpb" -> "Potato Books";
            case "reforge" -> k != null ? "Reforge " + pretty(k) : "Reforge";
            case "pet_item" -> k != null ? pretty(stripPrefix(k, "pet_item_")) : "Pet Item";
            case "tier" -> "Rarity Tier";
            default -> k != null ? pretty(k) : (type != null ? pretty(type) : null);
        };
    }

    /**
     * Whether {@code you}/{@code they} are coin costs rather than counts: enchants and gems carry
     * their cost there, so only the weight {@code w} is worth showing.
     */
    public static boolean valuesAreCoins(String type) {
        return "ench".equals(type) || "gem".equals(type) || "gems".equals(type);
    }

    /** "ultimate_wise" / "WITHER_SHIELD_SCROLL" → "Ultimate Wise" / "Wither Shield Scroll". */
    public static String pretty(String id) {
        String renamed = ID_RENAMES.get(id.toLowerCase(Locale.ROOT));
        if (renamed != null) {
            return renamed;
        }
        String[] words = id.toLowerCase(Locale.ROOT).split("[_\\s]+");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.toString();
    }

    private static String stripPrefix(String k, String prefix) {
        return k.toLowerCase(Locale.ROOT).startsWith(prefix) ? k.substring(prefix.length()) : k;
    }
}
