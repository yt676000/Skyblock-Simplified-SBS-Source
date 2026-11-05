/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.pricehistory.logic;

import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.item.ItemAnalysis;
import sbs.modid.client.core.item.SkyblockItem;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Resolves the price-API item id(s) for a hovered stack. The API keys items by SkyBlock id
 * ({@code ExtraAttributes.id}) but uses composed ids for the container-style items whose plain id
 * is shared by thousands of variants:
 * <ul>
 *   <li>pets: {@code PET_ENDER_DRAGON} (type from the {@code petInfo} JSON),</li>
 *   <li>enchanted books: {@code ENCHANTED_BOOK_ULTIMATE_LEGION_5} (single enchantment + level),</li>
 *   <li>runes: {@code RUNE_MUSIC_3} (rune name + tier).</li>
 * </ul>
 * Those composed ids are put first, then {@link SkyblockItem#priceLookupCandidates} (plain id,
 * normalized display name, vanilla registry fallback); the view tries each until the API knows one.
 */
public final class PriceLookup {

    private PriceLookup() {
    }

    /**
     * Ordered, de-duplicated API-id candidates for the stack (empty for empty stacks). Immutable;
     * computed once per item by {@link ItemAnalysis}, which owns the pet, book and rune forms.
     */
    public static List<String> candidatesFor(ItemStack stack) {
        return ItemAnalysis.of(stack).priceKeys();
    }

    /** API-id candidates for a Recipe Viewer item reference (catalogue id first, then its name). */
    public static List<String> candidatesFor(sbs.modid.client.economy.recipe.model.ItemRef ref) {
        if (ref == null) {
            return List.of();
        }
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        String id = ref.lookupId();
        if (id != null && !id.isEmpty()) {
            candidates.add(id.toUpperCase(Locale.ROOT));
        }
        String normalized = SkyblockItem.normalizeName(ref.name);
        if (!normalized.isEmpty()) {
            candidates.add(normalized);
        }
        return new ArrayList<>(candidates);
    }
}
