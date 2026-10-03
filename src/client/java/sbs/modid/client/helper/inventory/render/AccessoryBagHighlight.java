/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.inventory.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.item.SkyblockItem;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Highlights <b>duplicate accessories</b> in Hypixel's Accessory Bag menu, independent of the Recipe
 * Viewer's search highlight: any accessory whose SkyBlock id appears more than once on the open bag
 * page is boxed, so the copies you can safely sell (or that are dead Magical Power) jump out.
 *
 * <p>Only the bag's own slots are considered – the player-inventory slots at the bottom are skipped,
 * so a piece you carry in your inventory never counts against the bag. Detection is by SkyBlock id
 * ({@code ExtraAttributes.id}), which is exact; a page only sees itself, so duplicates split across
 * pages are found as each page is opened.
 *
 * <p>Drawn at the tail of {@code extractSlots} (the slot-relative space vanilla draws its own slots
 * in), exactly like the Bazaar highlighter, so the box lands on the item and never off to the side.
 */
public final class AccessoryBagHighlight {

    private AccessoryBagHighlight() {
    }

    private static SBSConfig.AccessoryBagSettings cfg() {
        return ConfigManager.getInstance().get().accessoryBag;
    }

    /** Called from {@link AccessoryBagDecorator} at the tail of every container's slot render. */
    public static void render(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g) {
        SBSConfig.AccessoryBagSettings cfg = cfg();
        if (!cfg.enabled || !cfg.highlightDuplicates) {
            return;
        }
        String title = screen.getTitle() != null ? screen.getTitle().getString() : "";
        if (!title.toLowerCase(Locale.ROOT).contains("accessory bag")) {
            return;
        }
        AbstractContainerMenu menu = screen.getMenu();

        // First pass: count SkyBlock ids among the bag's own slots (skip the player inventory).
        Map<String, Integer> counts = new HashMap<>();
        for (Slot slot : menu.slots) {
            if (slot.container instanceof Inventory) {
                continue;
            }
            String id = idOf(slot.getItem());
            if (!id.isEmpty()) {
                counts.merge(id, 1, Integer::sum);
            }
        }

        // Second pass: box every slot whose id was seen more than once.
        int frame = 0xFF000000 | (cfg.duplicateColor.argb() & 0xFFFFFF);
        int fill = 0x55000000 | (cfg.duplicateColor.argb() & 0xFFFFFF);
        for (Slot slot : menu.slots) {
            if (slot.container instanceof Inventory) {
                continue;
            }
            String id = idOf(slot.getItem());
            if (!id.isEmpty() && counts.getOrDefault(id, 0) >= 2) {
                g.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, fill);
                g.outline(slot.x - 1, slot.y - 1, 18, 18, frame);
            }
        }
    }

    /** The item's SkyBlock id ({@code ExtraAttributes.id}), or empty for a vanilla / menu item. */
    private static String idOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        return SkyblockItem.extraAttributes(stack).getStringOr("id", "");
    }
}
