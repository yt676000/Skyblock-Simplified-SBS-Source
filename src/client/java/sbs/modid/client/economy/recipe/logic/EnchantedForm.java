/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.recipe.logic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import sbs.modid.client.economy.recipe.model.ItemRef;
import sbs.modid.client.economy.recipe.model.SbsRecipe;

/**
 * The pure half of the enchanted-form hotkey: which of an item's usages is its compaction recipe -
 * Wheat → Enchanted Wheat, Enchanted Wheat → Enchanted Hay Bale. No Minecraft types, so it is
 * unit-tested against real recipe-database entries.
 *
 * <p>A compaction recipe uses nothing but the item itself, at least {@link #MIN_INPUT} of it, and
 * yields an {@code ENCHANTED_} item. The count floor is what keeps Enchanted Shears (two Enchanted
 * Iron) out, and the prefix keeps Bread, Hay Bale and Golem armour out.
 */
public final class EnchantedForm {

    /** The smallest compaction in the game is 32 of an item; crafting recipes use far fewer. */
    static final int MIN_INPUT = 32;

    private static final String PREFIX = "ENCHANTED_";

    private EnchantedForm() {
    }

    /**
     * The compaction recipes among {@code usages} (the recipes that use {@code id}), best first:
     * the output named {@code ENCHANTED_<id>} leads, the rest by smallest input count. Empty when
     * the item has no enchanted form.
     */
    public static List<SbsRecipe> candidates(String id, List<SbsRecipe> usages) {
        if (id == null || id.isEmpty() || usages == null || usages.isEmpty()) {
            return List.of();
        }
        String named = PREFIX + id;
        List<SbsRecipe> found = new ArrayList<>();
        for (SbsRecipe recipe : usages) {
            if (isCompaction(id, recipe)) {
                found.add(recipe);
            }
        }
        found.sort(Comparator.<SbsRecipe>comparingInt(r -> named.equals(r.result.lookupId()) ? 0 : 1)
                .thenComparingInt(EnchantedForm::inputCount)
                .thenComparing(r -> r.result.lookupId()));
        return found;
    }

    /** The best compaction recipe's output, or {@code null} when there is none. */
    public static ItemRef best(String id, List<SbsRecipe> usages) {
        List<SbsRecipe> found = candidates(id, usages);
        return found.isEmpty() ? null : found.get(0).result;
    }

    private static boolean isCompaction(String id, SbsRecipe recipe) {
        if (recipe == null || recipe.result == null || recipe.ingredients == null || recipe.ingredients.isEmpty()) {
            return false;
        }
        String out = recipe.result.lookupId();
        if (!out.startsWith(PREFIX) || out.equals(id)) {
            return false;
        }
        for (ItemRef ingredient : recipe.ingredients) {
            if (ingredient == null || !id.equals(ingredient.lookupId())) {
                return false;
            }
        }
        return inputCount(recipe) >= MIN_INPUT;
    }

    private static int inputCount(SbsRecipe recipe) {
        int total = 0;
        for (ItemRef ingredient : recipe.ingredients) {
            total += ingredient.count;
        }
        return total;
    }
}
