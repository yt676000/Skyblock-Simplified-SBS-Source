/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.recipe.logic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A pet's tooltip at a given level and rarity, from the recipe repo's pet data - pure, so every rule
 * is unit-tested against a captured in-game pet.
 *
 * <p><b>The data</b> (NEU repository, the same download as the rest of the recipe data):
 * <ul>
 *   <li>{@code items/<TYPE>;<rarity>.json}: per rarity, a name ({@code §7[Lvl {LVL}] §6Golden Dragon})
 *       and lore with placeholders - {@code {LVL}}, stat keys ({@code {STRENGTH}}) and ability numbers
 *       ({@code {0}}, {@code {1}} ...).</li>
 *   <li>{@code constants/petnums.json}: per pet and rarity, the values at table level {@code "1"} and
 *       {@code "100"} ({@code statNums} by key, {@code otherNums} by index), and for pets whose stats
 *       only start later a {@code stats_levelling_curve} {@code "a:b:c"}: table levels 1-100 are
 *       spread over real levels a-b (Golden Dragon: {@code "101:200:1"}).</li>
 *   <li>{@code constants/pets.json}: {@code custom_pet_leveling.<TYPE>.max_level} (200 for the
 *       Golden, Jade and Rose Dragons); every other pet stops at 100.</li>
 * </ul>
 * Between table levels 1 and 100 a value is linear. Numbers are shown the way Hypixel shows them: at
 * most two decimals, no trailing zeros ({@code +255.56}, {@code +50}). A placeholder with no data is
 * drawn as {@code ?} - never a guessed number - and reported to the caller to log.
 */
public final class PetLore {

    /** Rarity names by the index in a repo pet id ({@code GOLDEN_DRAGON;4} = LEGENDARY). */
    public static final String[] RARITIES = {"COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC"};

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Z_0-9]+)}");
    /** The item form's own hint - wrong in the viewer, where nothing is right-clicked into a menu. */
    private static final String ITEM_HINT = "Right-click to add this pet";

    private PetLore() {
    }

    /** The table level (1-100) a real level reads its numbers at. */
    public static int tableLevel(int level, String curve) {
        if (curve != null) {
            String[] parts = curve.split(":");
            try {
                int start = Integer.parseInt(parts[0].trim());
                return clamp(level - start + 1);
            } catch (RuntimeException malformed) {
                // fall through to the plain curve
            }
        }
        return clamp(level);
    }

    private static int clamp(int t) {
        return Math.max(1, Math.min(100, t));
    }

    /** The pet's max level: {@code custom_pet_leveling.<TYPE>.max_level}, else 100. */
    public static int maxLevel(JsonObject petConstants, String type) {
        if (petConstants != null && petConstants.has("custom_pet_leveling")) {
            JsonObject custom = petConstants.getAsJsonObject("custom_pet_leveling");
            if (custom.has(type)) {
                JsonObject entry = custom.getAsJsonObject(type);
                if (entry.has("max_level")) {
                    return entry.get("max_level").getAsInt();
                }
            }
        }
        return 100;
    }

    /**
     * One number at {@code level}, or {@code NaN} when the data has none.
     *
     * @param rarityNums {@code petnums.<TYPE>.<RARITY>}
     * @param key        a stat key ({@code STRENGTH}) or an ability index ({@code 0})
     */
    public static double valueAt(JsonObject rarityNums, String key, int level) {
        if (rarityNums == null || !rarityNums.has("1") || !rarityNums.has("100")) {
            return Double.NaN;
        }
        String curve = rarityNums.has("stats_levelling_curve")
                ? rarityNums.get("stats_levelling_curve").getAsString() : null;
        int t = tableLevel(level, curve);
        double low = number(rarityNums.getAsJsonObject("1"), key);
        double high = number(rarityNums.getAsJsonObject("100"), key);
        if (Double.isNaN(low) || Double.isNaN(high)) {
            return Double.NaN;
        }
        return low + (high - low) * (t - 1) / 99.0;
    }

    private static double number(JsonObject table, String key) {
        if (table == null) {
            return Double.NaN;
        }
        if (Character.isDigit(key.charAt(0))) {
            JsonArray other = table.has("otherNums") ? table.getAsJsonArray("otherNums") : null;
            int index = Integer.parseInt(key);
            return other != null && index < other.size() ? other.get(index).getAsDouble() : Double.NaN;
        }
        JsonObject stats = table.has("statNums") ? table.getAsJsonObject("statNums") : null;
        JsonElement value = stats == null ? null : stats.get(key);
        return value == null ? Double.NaN : value.getAsDouble();
    }

    /** Hypixel's number shape: at most two decimals, no trailing zeros; {@code ?} for no data. */
    public static String format(double value) {
        if (Double.isNaN(value)) {
            return "?";
        }
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    /**
     * The tooltip lines ({@code §}-coded): the name, then the lore, placeholders filled at {@code level}.
     *
     * @param missing receives every placeholder that had no data (to log once); may be {@code null}
     */
    public static List<String> build(String displayName, List<String> lore, JsonObject rarityNums, int level,
                                     Set<String> missing) {
        List<String> out = new ArrayList<>(lore.size() + 1);
        out.add(fill(displayName, rarityNums, level, missing));
        for (String line : lore) {
            if (line.contains(ITEM_HINT)) {
                continue;
            }
            out.add(fill(line, rarityNums, level, missing));
        }
        return out;
    }

    private static String fill(String line, JsonObject rarityNums, int level, Set<String> missing) {
        Matcher m = PLACEHOLDER.matcher(line);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String value;
            if (key.equals("LVL")) {
                value = String.valueOf(level);
            } else {
                double number = valueAt(rarityNums, key, level);
                if (Double.isNaN(number) && missing != null) {
                    missing.add(key);
                }
                value = format(number);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }
}
