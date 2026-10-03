/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.enchants;

import sbs.modid.client.economy.recipe.logic.EnchantmentRecipeProvider;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * THE single source of truth for enchant "actual name vs shown name" across the whole mod.
 *
 * <p>Hypixel's NBT/product ids and the in-game display names disagree in three ways:
 * <ul>
 *   <li>complete renames: {@code ultimate_reiterate} shows as <b>Duplex</b>,
 *       {@code dragon_hunter} as <b>Gravity</b>;</li>
 *   <li>most ultimate enchants DROP their prefix in lore ({@code ultimate_legion} → "Legion",
 *       {@code ultimate_wisdom} → "Wisdom", {@code ultimate_fatal_tempo} → "Fatal Tempo") -
 *       except a few whose real name contains it ({@code ultimate_wise} = "Ultimate Wise",
 *       {@code ultimate_jerry} = "Ultimate Jerry");</li>
 *   <li>display uses spaces/title case, ids use lower_snake_case.</li>
 * </ul>
 *
 * Every feature that maps between the two worlds (tooltip chroma, missing-enchant list, bazaar
 * order tracker, Recipe Viewer entries, flip/auction displays) goes through this class, so a
 * future Hypixel rename is fixed in exactly one map.
 */
public final class EnchantNames {

    /** NBT key → real in-game name (complete renames). */
    private static final Map<String, String> NBT_TO_DISPLAY = Map.of(
            "ultimate_reiterate", "Duplex",
            "dragon_hunter", "Gravity");

    /** Display name (lower) → NBT key, the reverse of {@link #NBT_TO_DISPLAY}. */
    private static final Map<String, String> DISPLAY_TO_NBT = Map.of(
            "duplex", "ultimate_reiterate",
            "gravity", "dragon_hunter");

    /** Ultimate enchants whose display name KEEPS the "Ultimate" prefix. */
    private static final Set<String> KEEPS_ULTIMATE_PREFIX =
            Set.of("ultimate_wise", "ultimate_jerry");

    private EnchantNames() {
    }

    /** True for ultimate enchants (one per item); NBT ids all carry the prefix. */
    public static boolean isUltimate(String nbtKey) {
        return nbtKey != null && nbtKey.toLowerCase(Locale.ROOT).startsWith("ultimate_");
    }

    /** The in-game display name for an NBT/product enchant key ("ultimate_fatal_tempo" → "Fatal Tempo"). */
    public static String displayName(String nbtKey) {
        String key = nbtKey == null ? "" : nbtKey.toLowerCase(Locale.ROOT);
        String renamed = NBT_TO_DISPLAY.get(key);
        if (renamed != null) {
            return renamed;
        }
        String base = key;
        if (base.startsWith("ultimate_") && !KEEPS_ULTIMATE_PREFIX.contains(base)) {
            base = base.substring("ultimate_".length());
        }
        return title(base);
    }

    /**
     * Lower-case name variants the lore/GUIs may use for an NBT key - for text matching
     * (chroma highlighting etc.). Includes the real display name plus defensive fallbacks.
     */
    public static Set<String> displayCandidates(String nbtKey) {
        String key = nbtKey == null ? "" : nbtKey.toLowerCase(Locale.ROOT);
        Set<String> names = new LinkedHashSet<>();
        names.add(displayName(key).toLowerCase(Locale.ROOT));
        names.add(key.replace('_', ' '));
        if (key.startsWith("ultimate_")) {
            names.add(key.substring("ultimate_".length()).replace('_', ' '));
        }
        return names;
    }

    /**
     * Resolves a DISPLAY base name (lower_snake, no level) back to the NBT/product enchant name:
     * "wisdom" → "ultimate_wisdom", "duplex" → "ultimate_reiterate", "sharpness" → "sharpness".
     * Candidates are validated against the known bazaar product registry; while that has not
     * loaded yet the plain name passes through unchanged.
     */
    public static String resolveBase(String displayBase) {
        String base = displayBase == null ? "" : displayBase.toLowerCase(Locale.ROOT);
        String special = DISPLAY_TO_NBT.get(base.replace('_', ' ').trim().replace(' ', '_'));
        if (special == null) {
            special = DISPLAY_TO_NBT.get(base);
        }
        if (special != null) {
            return special;
        }
        if (EnchantmentRecipeProvider.maxLevelOf(base) > 0) {
            return base;
        }
        String ultimate = "ultimate_" + base;
        if (EnchantmentRecipeProvider.maxLevelOf(ultimate) > 0) {
            return ultimate;
        }
        return base;
    }

    /** "fatal_tempo" → "Fatal Tempo". */
    private static String title(String lowerSnake) {
        StringBuilder sb = new StringBuilder();
        for (String word : lowerSnake.split("_")) {
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
}
