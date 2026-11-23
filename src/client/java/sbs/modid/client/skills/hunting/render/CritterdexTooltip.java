/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.hunting.render;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.economy.prices.ItemPriceKey;
import sbs.modid.client.skills.hunting.logic.CritterCatalog;

import java.util.ArrayList;
import java.util.List;

/**
 * Catch hints on the Critterdex for critters you have not caught yet.
 *
 * <p>Hypixel prints "Where to catch / How to catch" only on a critter already caught. This appends
 * the same hint (from {@link CritterCatalog}) when the hovered critter's own lore does not carry it,
 * and adds nothing when it does - once caught, the game tells you itself.
 *
 * <p>Called from {@code PriceTooltipMixin}'s single {@code RETURN} handler, like
 * {@link HuntingBoxTooltip}, for the reason given there.
 */
public final class CritterdexTooltip {

    private static final int HEADER = 0x55FFFF;
    private static final int LABEL = 0xFFAA00;
    private static final int BODY = 0xAAAAAA;

    private CritterdexTooltip() {
    }

    /** {@code lines} with the catch hint appended, or the same list when there is nothing to add. */
    public static List<Component> decorate(List<Component> lines, ItemStack stack) {
        if (lines == null || stack == null || stack.isEmpty()
                || !ConfigManager.getInstance().get().critterFinder.catchHints) {
            return lines;
        }
        if (!(GuiStateManager.getInstance().getCurrentScreen()
                instanceof AbstractContainerScreen<?> screen)) {
            return lines;
        }
        String title = screen.getTitle() == null ? "" : plain(screen.getTitle().getString());
        if (!title.startsWith("Critterdex")) {
            return lines;
        }
        List<String> lore = ItemPriceKey.lore(stack);
        for (String line : lore) {
            if (plain(line).startsWith("How to catch")) {
                return lines;   // caught: the menu already shows the hint
            }
        }

        // Name first; a hidden critter has no usable name, so fall back to biome page + slot.
        CritterCatalog.Critter critter = CritterCatalog.byName(plain(stack.getHoverName().getString()));
        if (critter == null) {
            String biome = header(screen);
            Slot slot = slotOf(screen, stack);
            if (biome == null || slot == null) {
                return lines;
            }
            critter = CritterCatalog.bySlot(biome, slot.index);
        }
        if (critter == null) {
            return lines;
        }

        List<Component> out = new ArrayList<>(lines);
        out.add(Component.empty());
        out.add(text("SBS Hint: " + critter.name() + " (not caught yet)", HEADER));
        out.add(text("Where to catch:", LABEL));
        out.add(text(critter.biome(), BODY));
        out.add(text("How to catch:", LABEL));
        for (String hint : critter.howTo()) {
            out.add(text(hint, BODY));
        }
        out.add(text(critter.rarity(), BODY));
        return out;
    }

    /** The biome named by the page header in slot 4 ("Icy Biome"), or null off a biome page. */
    private static String header(AbstractContainerScreen<?> screen) {
        var slots = screen.getMenu().slots;
        if (slots.size() <= 4) {
            return null;
        }
        String name = plain(slots.get(4).getItem().getHoverName().getString());
        // The sparkling pages share the layout but are a separate, optional collection.
        if (!name.endsWith(" Biome") || name.toUpperCase().startsWith("SPARKLING")) {
            return null;
        }
        return name;
    }

    private static Slot slotOf(AbstractContainerScreen<?> screen, ItemStack stack) {
        for (Slot slot : screen.getMenu().slots) {
            if (slot.getItem() == stack) {
                return slot;
            }
        }
        return null;
    }

    private static String plain(String text) {
        String stripped = ChatFormatting.stripFormatting(text);
        return stripped == null ? "" : stripped.trim();
    }

    private static Component text(String content, int color) {
        return Component.literal(content).setStyle(Style.EMPTY.withColor(TextColor.fromRgb(color)));
    }
}
