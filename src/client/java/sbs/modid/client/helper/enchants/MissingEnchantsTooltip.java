/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.enchants;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.item.SkyblockItem;
import sbs.modid.client.core.item.TooltipLines;
import sbs.modid.client.economy.prices.PriceVersion;
import sbs.modid.client.economy.recipe.logic.EnchantmentRecipeProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * "Hold Shift: Missing Enchantments" - the enchants this item id can carry (every enchant the SBS
 * database has seen on this exact item) but currently lacks, appended by
 * {@code EnchantTooltipMixin} while Shift is held. Built once per stack, data version (the pool
 * arriving, max levels) and config revision, not once per frame.
 */
public final class MissingEnchantsTooltip {

    private static final int MISSING_HEADER = 0xFF3FB4FF;
    private static final int MISSING_TEXT = 0xFF8FA9C8;

    private static final TooltipLines CACHE = new TooltipLines(MissingEnchantsTooltip::build);

    private MissingEnchantsTooltip() {
    }

    /** The lines to append for this stack; empty when it has none (no id, a book, a pet, a rune). */
    public static List<Component> lines(ItemStack stack) {
        long key = TooltipLines.key(PriceVersion.get(), ConfigManager.getInstance().revision(), 0);
        return CACHE.get(stack, key);
    }

    private static List<Component> build(ItemStack stack) {
        CompoundTag extra = SkyblockItem.extraAttributes(stack);
        List<Component> lines = new ArrayList<>();
        appendMissing(lines, extra, extra.getCompoundOrEmpty("enchantments"));
        return List.copyOf(lines);
    }

    private static boolean appendMissing(List<Component> lines, CompoundTag extra,
                                             CompoundTag enchants) {
        String itemId = extra.getStringOr("id", "");
        if (itemId.isEmpty() || "ENCHANTED_BOOK".equalsIgnoreCase(itemId)
                || "PET".equalsIgnoreCase(itemId) || "RUNE".equalsIgnoreCase(itemId)) {
            return false;
        }
        Map<String, Integer> pool = EnchantPoolCache.getInstance().get(itemId);
        lines.add(Component.empty());
        if (pool == null) {
            lines.add(Component.literal("Missing Enchants: loading...")
                    .withStyle(style -> style.withColor(TextColor.fromRgb(MISSING_TEXT & 0xFFFFFF))));
            return true;
        }
        // Only one ultimate fits per item: once any ultimate is present, other ultimates are
        // not "missing" but alternatives - keep them out of the list.
        boolean hasUltimate = false;
        for (String key : enchants.keySet()) {
            if (EnchantNames.isUltimate(key)) {
                hasUltimate = true;
                break;
            }
        }
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, Integer> e : pool.entrySet()) {
            if (enchants.contains(e.getKey())) {
                continue;
            }
            if (hasUltimate && EnchantNames.isUltimate(e.getKey())) {
                continue;
            }
            // Show the enchant's real max level (bazaar registry, endcaps included) - the pool's
            // "max seen on this item" undershoots on thin data ("Fatal Tempo 4" vs real max 5).
            int level = Math.max(e.getValue(),
                    EnchantmentRecipeProvider.maxLevelOf(e.getKey()));
            missing.add(EnchantNames.displayName(e.getKey()) + " " + level);
        }
        if (missing.isEmpty()) {
            lines.add(Component.literal("Missing Enchants: none - fully enchanted")
                    .withStyle(style -> style.withColor(TextColor.fromRgb(0x57D977))));
            return true;
        }
        lines.add(Component.literal("Missing Enchants (" + missing.size() + ", max seen):")
                .withStyle(style -> style.withColor(TextColor.fromRgb(MISSING_HEADER & 0xFFFFFF))));
        StringBuilder row = new StringBuilder();
        for (String entry : missing) {
            if (row.length() > 0 && row.length() + entry.length() > 44) {
                addMuted(lines, row.toString());
                row.setLength(0);
            }
            if (row.length() > 0) {
                row.append(", ");
            }
            row.append(entry);
        }
        if (row.length() > 0) {
            addMuted(lines, row.toString());
        }
        return true;
    }

    private static void addMuted(List<Component> lines, String text) {
        lines.add(Component.literal("  " + text)
                .withStyle(style -> style.withColor(TextColor.fromRgb(MISSING_TEXT & 0xFFFFFF))));
    }
}
