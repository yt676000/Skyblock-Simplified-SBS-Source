/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.foraging.model;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.item.SkyblockItem;

import java.util.Locale;

/**
 * "Is the player holding an axe" - the one item question the foraging features ask.
 *
 * <p><b>Deliberately not a list of SkyBlock ids.</b> {@code FarmingItems} can name its tools because
 * the farming ones have been stable for years and every one of them has been seen; Galatea's axes
 * are new, and a list written from memory would be a list of guesses that silently fails to
 * recognise the axe the player is actually holding. So this asks two questions that stay true
 * without anybody having catalogued anything:
 *
 * <ol>
 *   <li>the <b>vanilla item</b> the stack is built on - {@code minecraft:diamond_axe} and friends,
 *       read out of the registry rather than off a class, since which class an axe is has moved
 *       between versions;</li>
 *   <li>the <b>SkyBlock id</b> containing {@code AXE}, which covers every named axe Hypixel ships
 *       without needing to know it exists.</li>
 * </ol>
 *
 * <p>{@link #NAMED} holds only the handful whose id says nothing about being an axe. It is a
 * fallback for real exceptions, not the primary mechanism, and it is short on purpose: an entry here
 * is a claim about a specific item, and every claim can be wrong.
 */
public final class ForagingItems {

    /** Axes whose SkyBlock id does not contain "AXE". Confirmed items only - not a wish list. */
    private static final String[] NAMED = {
            "TREECAPITATOR",
    };

    private ForagingItems() {
    }

    /** Whether {@code stack} is an axe by either the vanilla item or the SkyBlock id. */
    public static boolean isAxe(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        String skyblockId = SkyblockItem.id(stack);
        if (skyblockId != null && !skyblockId.isEmpty()) {
            String upper = skyblockId.toUpperCase(Locale.ROOT);
            if (upper.contains("AXE")) {
                return true;
            }
            for (String named : NAMED) {
                if (upper.contains(named)) {
                    return true;
                }
            }
        }
        return isVanillaAxe(stack);
    }

    /**
     * The vanilla item behind the stack, by registry path.
     *
     * <p>Matched on the path ending in {@code _axe} rather than on an item class: the class an axe is
     * an instance of has changed between Minecraft versions, and the registry name has not.
     */
    private static boolean isVanillaAxe(ItemStack stack) {
        Identifier key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (key == null) {
            return false;
        }
        String path = key.getPath();
        return path.equals("axe") || path.endsWith("_axe");
    }
}
