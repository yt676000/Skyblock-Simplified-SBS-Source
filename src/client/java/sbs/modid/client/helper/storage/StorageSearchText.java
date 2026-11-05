/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.storage;

import java.util.Locale;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.item.SkyblockItem;
import sbs.modid.client.core.item.StackCache;
import sbs.modid.client.economy.itemvalue.ItemModifiers;
import sbs.modid.client.helper.enchants.counter.CounterEnchantXp;

/**
 * What a storage search matches a stack against: its stripped, lower-cased name followed by every
 * enchantment as {@code "<name> <level> <name> <roman>"} – so "ultimate wise", "legion 5" and
 * "sharpness vii" find books (all named "Enchanted Book") and the gear that carries the enchant.
 *
 * <p>Matching stays a plain substring test: "sharpness 6" does not find Sharpness VII. Cached per
 * name and custom-data identity, so a cell rebuilds only when its item changes.
 */
final class StorageSearchText {

    private static final StackCache<String> CACHE = new StackCache<>(512,
            stack -> build(StorageIndex.strip(stack.getHoverName().getString()),
                    SkyblockItem.extraAttributes(stack)),
            DataComponents.CUSTOM_NAME, DataComponents.ITEM_NAME, DataComponents.CUSTOM_DATA);

    private StorageSearchText() {
    }

    static String of(ItemStack stack) {
        return CACHE.get(stack);
    }

    /** {@code extra} is the stack's own read-only tag; it is only read. */
    static String build(String name, CompoundTag extra) {
        StringBuilder sb = new StringBuilder(name.toLowerCase(Locale.ROOT));
        // get + instanceof: getCompoundOrEmpty allocates on the common no-enchant miss.
        if (extra.get("enchantments") instanceof CompoundTag enchants) {
            for (String key : enchants.keySet()) {
                int level = enchants.getIntOr(key, 0);
                if (level <= 0) {
                    continue;
                }
                String pretty = ItemModifiers.prettify(key).toLowerCase(Locale.ROOT);
                sb.append('\n').append(pretty).append(' ').append(level)
                        .append('\n').append(pretty).append(' ')
                        .append(CounterEnchantXp.roman(level).toLowerCase(Locale.ROOT));
            }
        }
        return sb.toString();
    }
}
