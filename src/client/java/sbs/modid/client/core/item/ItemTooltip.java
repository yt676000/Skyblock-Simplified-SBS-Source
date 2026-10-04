/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.item;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * An item's tooltip as a menu shows it, for SBS overlays that draw their own.
 *
 * <p>Goes through {@link Screen#getTooltipFromItem}, the path every container tooltip takes, so SBS's
 * own lines come along: prices, chest value, bits, museum, protection ({@code PriceTooltipMixin} at
 * {@code ItemStack.getTooltipLines} RETURN) and the enchant extras hooked on {@code getTooltipFromItem}
 * itself. A tooltip assembled by hand from the hover name and the {@code LORE} component has none of
 * them - that was the bug in the storage, loadouts and armor-set overlays.
 *
 * <p>Call it for the hovered stack only. It runs the price lookups, which is fine once per frame -
 * vanilla does the same - but not once per slot.
 */
public final class ItemTooltip {

    private ItemTooltip() {
    }

    /** The full tooltip, as a new mutable list; the hover name alone if building it fails. */
    public static List<Component> of(ItemStack stack) {
        try {
            return new ArrayList<>(Screen.getTooltipFromItem(Minecraft.getInstance(), stack));
        } catch (Throwable t) {
            // A tooltip contributor throwing must not take the overlay down with it.
            List<Component> fallback = new ArrayList<>();
            fallback.add(stack.getHoverName());
            return fallback;
        }
    }
}
