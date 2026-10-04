/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.dev;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;

/**
 * The per-slot and per-menu readers the menu capture tools share: {@link MenuProbe} (a text dump of
 * one menu) and the Server Scanner (a time series of every menu). One set of readers, so the two
 * captures cannot disagree about what a slot's item id or lore was.
 *
 * <p>Client thread only - every method reads a live {@link ItemStack} or menu.
 */
public final class MenuReads {

    private MenuReads() {
    }

    /** The vanilla registry key, e.g. {@code minecraft:red_stained_glass}. */
    public static String itemKey(ItemStack stack) {
        return String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    /** The raw {@link DataComponents#LORE} lines - what a parser reads, not the rendered tooltip. */
    public static List<Component> loreLines(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        return lore == null ? List.of() : lore.lines();
    }

    /**
     * Flattened text, with legacy {@code §} codes removed.
     *
     * <p>{@link Component#getString()} already drops style-based formatting; the codes removed here
     * are the ones Hypixel embeds in literal text.
     */
    public static String plain(Component component) {
        return component == null ? "" : component.getString().replaceAll("§.", "");
    }

    /** The menu type's registry key, or {@code (none)} for the player-inventory menu. */
    public static String menuType(AbstractContainerMenu menu) {
        try {
            var type = menu.getType();
            var key = BuiltInRegistries.MENU.getKey(type);
            return key == null ? String.valueOf(type) : key.toString();
        } catch (Throwable t) {
            // The player-inventory menu has no type and throws rather than returning null.
            return "(none)";
        }
    }

    /** Whether a slot belongs to the menu itself rather than to the player's inventory below it. */
    public static boolean isMenuSlot(Slot slot) {
        return !(slot.container instanceof Inventory);
    }

    /** How many of the menu's slots are the menu's own, by the same reckoning the features use. */
    public static int containerSlots(AbstractContainerMenu menu) {
        int count = 0;
        for (Slot slot : menu.slots) {
            if (isMenuSlot(slot)) {
                count++;
            }
        }
        return count;
    }
}
