/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.pricehistory.logic;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
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

    /** Ordered, de-duplicated API-id candidates for the stack (empty for empty stacks). */
    public static List<String> candidatesFor(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        CompoundTag extra = extraAttributes(stack);
        String id = extra.getStringOr("id", "").toUpperCase(Locale.ROOT);
        switch (id) {
            case "PET" -> {
                String type = petType(extra);
                if (type == null || type.isEmpty()) {
                    type = SkyblockItem.normalizeName(stack.getHoverName().getString());
                }
                if (!type.isEmpty()) {
                    candidates.add("PET_" + type.toUpperCase(Locale.ROOT));
                }
            }
            case "ENCHANTED_BOOK" -> {
                String book = singleEntryId(extra.getCompoundOrEmpty("enchantments"), "ENCHANTED_BOOK_");
                if (book != null) {
                    candidates.add(book);
                }
            }
            case "RUNE", "UNIQUE_RUNE" -> {
                String rune = singleEntryId(extra.getCompoundOrEmpty("runes"), "RUNE_");
                if (rune != null) {
                    candidates.add(rune);
                }
            }
            default -> {
            }
        }
        candidates.addAll(SkyblockItem.priceLookupCandidates(stack));
        return new ArrayList<>(candidates);
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

    /** Delegates to the shared reader, which also understands the modern flattened custom data. */
    private static CompoundTag extraAttributes(ItemStack stack) {
        return SkyblockItem.extraAttributes(stack);
    }

    /** The {@code "type"} of the {@code petInfo} JSON string ("ENDER_DRAGON"), or {@code null}. */
    private static String petType(CompoundTag extra) {
        String petInfo = extra.getStringOr("petInfo", "");
        if (petInfo.isEmpty()) {
            return null;
        }
        try {
            JsonObject info = JsonParser.parseString(petInfo).getAsJsonObject();
            return info.has("type") ? info.get("type").getAsString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Composes {@code prefix + NAME + "_" + level} from a one-entry compound like
     * {@code enchantments: {ultimate_legion: 5}} / {@code runes: {MUSIC: 3}}, or {@code null}.
     */
    private static String singleEntryId(CompoundTag compound, String prefix) {
        for (String key : compound.keySet()) {
            int level = compound.getIntOr(key, 0);
            if (level > 0) {
                return prefix + key.toUpperCase(Locale.ROOT) + "_" + level;
            }
        }
        return null;
    }
}
