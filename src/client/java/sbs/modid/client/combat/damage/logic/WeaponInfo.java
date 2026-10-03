/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.damage.logic;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.combat.damage.model.GearStats;
import sbs.modid.client.core.item.SkyblockItem;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Everything the damage estimate needs to know about the held weapon: its lore stats (via
 * {@link GearStats} – final values with reforge and gems folded in) and the enchantment map from
 * the SkyBlock NBT ({@code enchantments}, lower-case keys, int levels – the same compound the
 * item-value breakdown already reads).
 */
public record WeaponInfo(String id, GearStats gear, Map<String, Integer> enchants) {

    /** Parses the held stack; {@code null} when it is not a SkyBlock item (empty hand, vanilla junk). */
    public static WeaponInfo of(ItemStack stack) {
        String id = SkyblockItem.id(stack);
        if (id == null) {
            return null;
        }
        Map<String, Integer> enchants = new HashMap<>();
        CompoundTag compound = SkyblockItem.extraAttributes(stack).getCompoundOrEmpty("enchantments");
        for (String key : compound.keySet()) {
            int level = compound.getIntOr(key, 0);
            if (level > 0) {
                enchants.put(key.toLowerCase(Locale.ROOT), level);
            }
        }
        return new WeaponInfo(id, GearStats.of(stack), enchants);
    }

    public double damage() {
        return gear.damage();
    }

    /** The level of {@code enchant} on this weapon, 0 when absent. */
    public int enchant(String enchant) {
        return enchants.getOrDefault(enchant, 0);
    }
}
