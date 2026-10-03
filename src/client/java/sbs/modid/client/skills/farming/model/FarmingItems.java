/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.farming.model;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.item.SkyblockItem;

import java.util.Locale;
import java.util.Set;

/**
 * Recognises Hypixel SkyBlock farming tools from a held {@link ItemStack}.
 *
 * <p>Detection is by SkyBlock item id rather than the vanilla material, because a Hoe of Growth and
 * a vanilla hoe share the same base item while only the former should count. Ids are matched by
 * <b>suffix/prefix family</b> instead of an exhaustive list, so new tiers Hypixel adds (the tools
 * are consistently named {@code *_HOE}, {@code THEORETICAL_HOE_*}, ...) are picked up without a code
 * change.
 *
 * <p>Kept separate from the features that use it so any future farming helper can ask the same
 * question and get the same answer.
 */
public final class FarmingItems {

    /** Exact ids that don't follow one of the naming families below. */
    private static final Set<String> EXACT = Set.of(
            "COCO_CHOPPER",
            "MELON_DICER",
            "MELON_DICER_2",
            "MELON_DICER_3",
            "PUMPKIN_DICER",
            "PUMPKIN_DICER_2",
            "PUMPKIN_DICER_3",
            "FUNGI_CUTTER",
            "BINGHOE",
            "PRISMAPUMP",
            "ROOKIE_HOE",
            "GARDENING_AXE",
            "GARDENING_HOE",
            "NETHER_WARTS_HOE",
            "ADVANCED_GARDENING_AXE",
            "ADVANCED_GARDENING_HOE");

    /** Id families: any id containing one of these is a farming tool. */
    private static final String[] FAMILIES = {
            "_HOE",          // TURING_HOE_..., NEWTON_NETHER_WARTS_HOE, ...
            "THEORETICAL_HOE",
            "_AXE_",         // (jungle axe family variants)
            "JUNGLE_AXE",
            "TREECAPITATOR",
            "WHEAT_HOE",
            "CARROT_HOE",
            "POTATO_HOE",
            "CACTUS_KNIFE",
            "SUGAR_CANE_HOE"};

    private FarmingItems() {
    }

    /** Whether the stack is a SkyBlock farming tool. Empty / non-SkyBlock stacks are never one. */
    public static boolean isFarmingTool(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        CompoundTag extra = SkyblockItem.extraAttributes(stack);
        String id = extra.getStringOr("id", "");
        if (id.isEmpty()) {
            return false;
        }
        String upper = id.toUpperCase(Locale.ROOT);
        if (EXACT.contains(upper)) {
            return true;
        }
        for (String family : FAMILIES) {
            if (upper.contains(family)) {
                return true;
            }
        }
        return false;
    }
}
