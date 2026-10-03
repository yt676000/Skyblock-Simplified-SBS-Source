/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.recipe.logic;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.economy.pricehistory.logic.PriceApi;
import sbs.modid.client.economy.recipe.model.ItemRef;
import sbs.modid.client.economy.recipe.model.SbsRecipe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Makes bazaar enchantment products (normal AND ultimate enchants) searchable in the Recipe Viewer
 * and exposes their combine recipe.
 *
 * <p>The official Hypixel items resource does not list enchantment books, so they were invisible to
 * the viewer. This provider pulls the full product list from the SBS API ({@code /api/items}, which
 * mirrors every bazaar product id like {@code ENCHANTMENT_ULTIMATE_LEGION_5}), registers one
 * catalogue {@link SkyBlockItemCatalog.Entry} per level (so search finds "Ultimate Legion 5") and
 * builds the standard combine recipe <b>2 × tier&nbsp;N-1 → tier&nbsp;N</b> for every consecutive
 * level pair.
 *
 * <p>The fetch is async and auth'd by the licence token; if it fails (no token yet at launch) it is
 * retried from {@link #loadRecipes()} until it succeeds, so entries appear once the token is set.
 */
public final class EnchantmentRecipeProvider implements RecipeProvider {

    private static final EnchantmentRecipeProvider INSTANCE = new EnchantmentRecipeProvider();

    private static final long RETRY_MS = 60_000L;
    private static final String BOOK_MATERIAL = "enchanted_book";


    private volatile List<SbsRecipe> recipes = List.of();
    private volatile boolean fetching;
    private volatile long lastAttemptMs;

    /** Enchant name (lower-case NBT id, e.g. "ultimate_legion") -> highest obtainable level,
     *  derived from the bazaar product list (includes endcap levels automatically). */
    private static volatile Map<String, Integer> maxLevels = Map.of();

    /** Enchant name (lower-case) -> {lowest, highest} level present as a bazaar product. */
    private static volatile Map<String, int[]> levelRanges = Map.of();

    /** Highest obtainable level of an enchant, or 0 while the list has not loaded yet. */
    public static int maxLevelOf(String enchantName) {
        Integer max = maxLevels.get(enchantName == null ? "" : enchantName.toLowerCase(Locale.ROOT));
        return max != null ? max : 0;
    }

    /**
     * The {lowest, highest} level of an enchant present as bazaar products, or {@code null} while the
     * list has not loaded. Used by the Recipe Viewer to label a grouped row "Legion 1–10".
     */
    public static int[] levelRangeOf(String enchantName) {
        return levelRanges.get(enchantName == null ? "" : enchantName.toLowerCase(Locale.ROOT));
    }

    // ------------------------------------------------------------------ grouping helpers

    /** Whether a lookup id is an enchant book product ("ENCHANTMENT_ULTIMATE_LEGION_5"). */
    public static boolean isEnchantId(String id) {
        return keyOf(id) != null;
    }

    /** The enchant KEY inside an id ("ENCHANTMENT_ULTIMATE_LEGION_5" -> "ULTIMATE_LEGION"), or null. */
    public static String keyOf(String id) {
        if (id == null || !id.startsWith("ENCHANTMENT_")) {
            return null;
        }
        String body = id.substring("ENCHANTMENT_".length());
        int split = body.lastIndexOf('_');
        if (split <= 0) {
            return null;
        }
        String level = body.substring(split + 1);
        if (level.isEmpty() || !level.chars().allMatch(Character::isDigit)) {
            return null;
        }
        return body.substring(0, split);
    }

    /** The in-game display name for an enchant KEY ("ULTIMATE_LEGION" -> "Legion"). */
    public static String prettyName(String key) {
        return prettyEnchant(key);
    }

    /**
     * A single grouped search row representing all levels of an enchant: the max-level id (so a click
     * opens its combine recipe) with a range label "Legion 1–10". Returns {@code null} if the level
     * list has not loaded yet, so the caller can fall back to the ungrouped ref.
     */
    public static ItemRef groupRef(String key) {
        if (key == null) {
            return null;
        }
        int[] range = levelRangeOf(key);
        if (range == null || range[1] <= 0) {
            return null;
        }
        String pretty = prettyEnchant(key);
        String label = range[0] == range[1]
                ? pretty + " " + range[1]
                : pretty + " " + range[0] + "–" + range[1]; // en dash
        return new ItemRef("ENCHANTMENT_" + key + "_" + range[1], BOOK_MATERIAL, label, 1);
    }

    /** The hover tooltip for a grouped enchant row: what the single line stands for. */
    public static java.util.List<net.minecraft.network.chat.Component> groupTooltip(String key) {
        java.util.List<net.minecraft.network.chat.Component> lines = new ArrayList<>();
        if (key == null) {
            return lines;
        }
        int[] range = levelRangeOf(key);
        String pretty = prettyEnchant(key);
        lines.add(net.minecraft.network.chat.Component.literal(pretty)
                .withColor(0x55FFFF)
                .append(net.minecraft.network.chat.Component.literal("  (enchant group)").withColor(0x7F7F7F)));
        if (range != null && range[1] > 0) {
            lines.add(net.minecraft.network.chat.Component.literal("Tiers " + range[0] + "–" + range[1]
                    + " grouped into one row").withColor(0xAAAAAA));
        }
        lines.add(net.minecraft.network.chat.Component.literal("Combine: 2× tier N-1 → tier N")
                .withColor(0xAAAAAA));
        lines.add(net.minecraft.network.chat.Component.literal("Opens the max-tier recipe; click an")
                .withColor(0x7F7F7F));
        lines.add(net.minecraft.network.chat.Component.literal("ingredient to walk down the tiers.")
                .withColor(0x7F7F7F));
        return lines;
    }

    private EnchantmentRecipeProvider() {
    }

    public static EnchantmentRecipeProvider getInstance() {
        return INSTANCE;
    }

    /** Kicks off the first fetch; safe to call once during client init. */
    public void start() {
        fetch();
    }

    @Override
    public String name() {
        return "Enchantments";
    }

    @Override
    public List<SbsRecipe> loadRecipes() {
        if (recipes.isEmpty() && !fetching
                && System.currentTimeMillis() - lastAttemptMs > RETRY_MS) {
            fetch(); // retry until the licence token is valid and the list arrives
        }
        return recipes;
    }

    private void fetch() {
        fetching = true;
        lastAttemptMs = System.currentTimeMillis();
        PriceApi.getInstance().fetchItems((items, error) -> {
            try {
                if (items != null) {
                    build(items);
                } else {
                    SkyblockSimplifiedSBS.LOGGER.info("[SBS][Enchants] item list unavailable ({}).",
                            error);
                }
            } catch (Throwable t) {
                SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Enchants] build failed", t);
            } finally {
                fetching = false;
            }
        });
    }

    /** Parses the {@code [id, type]} rows into catalogue entries + combine recipes. */
    private void build(String[][] items) {
        // enchant name -> sorted levels present as bazaar products
        Map<String, TreeSet<Integer>> byName = new HashMap<>();
        for (String[] row : items) {
            if (row == null || row.length == 0 || row[0] == null) {
                continue;
            }
            String id = row[0];
            if (!id.startsWith("ENCHANTMENT_")) {
                continue;
            }
            String body = id.substring("ENCHANTMENT_".length());
            int split = body.lastIndexOf('_');
            if (split <= 0) {
                continue;
            }
            String level = body.substring(split + 1);
            if (level.isEmpty() || !level.chars().allMatch(Character::isDigit)) {
                continue;
            }
            byName.computeIfAbsent(body.substring(0, split), k -> new TreeSet<>())
                    .add(Integer.parseInt(level));
        }
        if (byName.isEmpty()) {
            return;
        }

        Map<String, SkyBlockItemCatalog.Entry> entries = new HashMap<>();
        List<SbsRecipe> built = new ArrayList<>();
        for (Map.Entry<String, TreeSet<Integer>> e : byName.entrySet()) {
            String rawName = e.getKey();          // e.g. "ULTIMATE_LEGION"
            String pretty = prettyEnchant(rawName);
            Integer prev = null;
            for (int level : e.getValue()) {
                String id = "ENCHANTMENT_" + rawName + "_" + level;
                String display = pretty + " " + level;
                entries.put(id, new SkyBlockItemCatalog.Entry(id, display, BOOK_MATERIAL, null, null));
                // Combine recipe: 2x the previous consecutive level -> this level.
                if (prev != null && prev == level - 1) {
                    String prevId = "ENCHANTMENT_" + rawName + "_" + prev;
                    SbsRecipe recipe = new SbsRecipe("Combine", "Bazaar combine",
                            new ItemRef(id, BOOK_MATERIAL, display, 1));
                    recipe.ingredients.add(new ItemRef(prevId, BOOK_MATERIAL,
                            pretty + " " + prev, 2));
                    built.add(recipe);
                }
                prev = level;
            }
        }

        SkyBlockItemCatalog.getInstance().addRepoEntries(entries);
        Map<String, Integer> max = new HashMap<>();
        Map<String, int[]> ranges = new HashMap<>();
        for (Map.Entry<String, TreeSet<Integer>> e : byName.entrySet()) {
            String lower = e.getKey().toLowerCase(Locale.ROOT);
            max.put(lower, e.getValue().last());
            ranges.put(lower, new int[]{e.getValue().first(), e.getValue().last()});
        }
        maxLevels = Map.copyOf(max);
        levelRanges = Map.copyOf(ranges);
        recipes = List.copyOf(built);
        RecipeRegistry.getInstance().markDirty();
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Enchants] {} enchant levels searchable, {} combine recipes.",
                entries.size(), built.size());
    }

    /** "ULTIMATE_REITERATE" -> "Duplex", "ULTIMATE_LEGION" -> "Legion" - the shared
     *  actual-vs-shown mapping, so the Recipe Viewer search finds the IN-GAME names. */
    private static String prettyEnchant(String rawName) {
        return sbs.modid.client.helper.enchants.EnchantNames.displayName(rawName.toLowerCase(Locale.ROOT));
    }
}
