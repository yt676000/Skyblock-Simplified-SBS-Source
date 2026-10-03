/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.itemprotection.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.item.SkyblockItem;
import sbs.modid.client.core.render.OverlayColor;
import sbs.modid.client.helper.itemprotection.logic.ItemProtection;
import sbs.modid.client.helper.itemprotection.logic.ProtectedItems;
import sbs.modid.client.helper.itemprotection.model.IndicatorStyle;
import sbs.modid.client.ui.render.SlotDecorations;
import sbs.modid.client.ui.render.SlotDecorator;

/**
 * Marks every protected slot in any open container.
 *
 * <p>Order 880 - above the item overlays that say what an item <i>is</i>, and below the slot-lock
 * padlock at 900, which has the stronger claim on staying readable because it is the one telling you
 * a slot will not move at all.
 *
 * <p>Also the place a protected item's remembered display name is refreshed: this runs for every
 * protected stack that is actually drawn, so a reforged or renamed item updates its entry simply by
 * being looked at, and the management screen can then list it correctly while the player is nowhere
 * near it. The store only marks itself dirty when the name really changed, so this costs no writes.
 */
public final class ProtectedItemDecorator implements SlotDecorator {

    /** ServiceLoader needs a public no-arg constructor. */
    public ProtectedItemDecorator() {
    }

    @Override
    public String id() {
        return "item_protection";
    }

    @Override
    public int order() {
        return 880;
    }

    @Override
    public void decorate(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g,
                         int mouseX, int mouseY) {
        SBSConfig.ItemProtectionSettings cfg = ConfigManager.getInstance().get().itemProtection;
        if (!cfg.indicator || !ItemProtection.enabled()) {
            return;
        }
        ProtectedItems store = ProtectedItems.getInstance();
        if (store.isEmpty()) {
            return;
        }
        IndicatorStyle style = cfg.indicatorStyle == null ? IndicatorStyle.BOTH : cfg.indicatorStyle;
        int color = color(cfg);

        AbstractContainerMenu menu = screen.getMenu();
        for (Slot slot : menu.slots) {
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            ItemProtection.Protection protection = ItemProtection.protectionOf(stack);
            if (protection == null) {
                continue;
            }
            if (protection.kind() == ItemProtection.Kind.ITEM) {
                store.seen(protection.key(), SkyblockItem.id(stack), protection.displayName());
            }
            if (style.drawsBorder()) {
                SlotDecorations.box(g, slot, 0, color);
            }
            if (style.drawsIcon()) {
                ProtectedItemIcon.draw(g, slot.x, slot.y, color);
            }
        }
    }

    /** The configured colour as opaque ARGB, falling back to the amber default. */
    private static int color(SBSConfig.ItemProtectionSettings cfg) {
        Integer rgb = OverlayColor.parseHex(cfg.indicatorColorHex);
        int value = rgb == null
                ? SBSConfig.ItemProtectionSettings.DEFAULT_INDICATOR_COLOR : rgb;
        return 0xFF000000 | value;
    }
}
