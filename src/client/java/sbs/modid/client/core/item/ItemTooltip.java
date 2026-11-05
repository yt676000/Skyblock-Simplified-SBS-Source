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
import net.minecraft.core.component.DataComponents;
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

    /**
     * The last tooltip built, for the stack hovered frame after frame. Valid while the stack and the
     * components its lines come from are the same instances, prices and config have not moved, the
     * modifier keys read the same, and it is younger than {@link #MAX_AGE_MS} - the bound for any
     * contributor whose line reads the clock. Client thread only.
     */
    private static ItemStack lastStack;
    private static Object lastLore;
    private static Object lastData;
    private static Object lastName;
    private static int lastCount;
    private static int lastPrices;
    private static int lastConfig;
    private static boolean lastShift;
    private static boolean lastCtrl;
    private static long lastBuiltAt;
    private static List<Component> lastLines;

    private static final long MAX_AGE_MS = 250L;

    /** The full tooltip, as a new mutable list; the hover name alone if building it fails. */
    public static List<Component> of(ItemStack stack) {
        Minecraft minecraft = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        Object lore = stack.get(DataComponents.LORE);
        Object data = stack.get(DataComponents.CUSTOM_DATA);
        Object name = stack.get(DataComponents.CUSTOM_NAME);
        int prices = sbs.modid.client.economy.prices.PriceVersion.get();
        int config = sbs.modid.client.core.config.ConfigManager.getInstance().revision();
        boolean shift = minecraft.hasShiftDown();
        boolean ctrl = minecraft.hasControlDown();
        if (lastLines != null && stack == lastStack && lore == lastLore && data == lastData && name == lastName
                && stack.getCount() == lastCount && prices == lastPrices && config == lastConfig
                && shift == lastShift && ctrl == lastCtrl && now - lastBuiltAt < MAX_AGE_MS) {
            return new ArrayList<>(lastLines);
        }
        List<Component> built = build(minecraft, stack);
        lastStack = stack;
        lastLore = lore;
        lastData = data;
        lastName = name;
        lastCount = stack.getCount();
        lastPrices = prices;
        lastConfig = config;
        lastShift = shift;
        lastCtrl = ctrl;
        lastBuiltAt = now;
        lastLines = new ArrayList<>(built);
        return built;
    }

    /** Lets go of the remembered stack - on screen close, with the other per-stack caches. */
    public static void clear() {
        lastStack = null;
        lastLines = null;
        lastLore = lastData = lastName = null;
    }

    private static List<Component> build(Minecraft minecraft, ItemStack stack) {
        try {
            return new ArrayList<>(Screen.getTooltipFromItem(minecraft, stack));
        } catch (Throwable t) {
            // A tooltip contributor throwing must not take the overlay down with it.
            List<Component> fallback = new ArrayList<>();
            fallback.add(stack.getHoverName());
            return fallback;
        }
    }
}
