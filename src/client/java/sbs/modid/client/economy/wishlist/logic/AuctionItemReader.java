/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.wishlist.logic;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import sbs.modid.client.core.net.NbtLimits;
import sbs.modid.client.core.net.validation.ValidationException;
import sbs.modid.client.economy.itemvalue.GemSlots;
import sbs.modid.client.economy.itemvalue.ItemModifiers;
import sbs.modid.client.economy.wishlist.model.AuctionItem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads an item's SkyBlock attributes into an {@link AuctionItem}: from an auction's
 * {@code item_bytes}, or from a live stack's attribute compound (the "add from held item" path).
 *
 * <p>Gems go through {@link ItemModifiers#readGems}, the one reader of the {@code gems} compound, so
 * the wishlist and the gem-slot tooltip can never disagree about what an item carries.
 */
public final class AuctionItemReader {

    private AuctionItemReader() {
    }

    /**
     * Decodes {@code base64(gzip(nbt))} auction bytes, through the size-limited decoder every remote
     * NBT blob in the mod goes through. {@code null} when the blob cannot be read.
     */
    public static AuctionItem fromItemBytes(String itemBytes) throws ValidationException {
        CompoundTag root = NbtLimits.decodeBase64Gzip(itemBytes);
        ListTag items = root.getListOrEmpty("i");
        if (items.isEmpty()) {
            return null;
        }
        CompoundTag tag = items.getCompoundOrEmpty(0).getCompoundOrEmpty("tag");
        return fromAttributes(tag.getCompoundOrEmpty("ExtraAttributes"));
    }

    /** Reads an attribute compound ({@code ExtraAttributes}, or the flattened custom data). */
    public static AuctionItem fromAttributes(CompoundTag extra) {
        Map<String, Integer> enchants = new HashMap<>();
        CompoundTag enchantTag = extra.getCompoundOrEmpty("enchantments");
        for (String key : enchantTag.keySet()) {
            int level = enchantTag.getIntOr(key, 0);
            if (level > 0) {
                enchants.put(key.toLowerCase(Locale.ROOT), level);
            }
        }
        GemSlots.Applied applied = ItemModifiers.readGems(extra);
        List<AuctionItem.Gem> gems = new ArrayList<>(applied.filled().size());
        for (GemSlots.Filled filled : applied.filled()) {
            gems.add(new AuctionItem.Gem(filled.slotKey(),
                    filled.gemType() == null ? "" : filled.gemType().toUpperCase(Locale.ROOT),
                    filled.quality().toUpperCase(Locale.ROOT)));
        }
        int stars = Math.max(extra.getIntOr("upgrade_level", 0), extra.getIntOr("dungeon_item_level", 0));
        return new AuctionItem(
                extra.getStringOr("id", ""),
                Map.copyOf(enchants),
                extra.getStringOr("modifier", "").toLowerCase(Locale.ROOT),
                stars,
                extra.getIntOr("rarity_upgrades", 0) > 0,
                extra.getIntOr("hot_potato_count", 0),
                Set.copyOf(applied.unlocked()),
                List.copyOf(gems),
                extra.getStringOr("dye_item", "").toLowerCase(Locale.ROOT),
                extra.getStringOr("skin", "").toLowerCase(Locale.ROOT));
    }
}
