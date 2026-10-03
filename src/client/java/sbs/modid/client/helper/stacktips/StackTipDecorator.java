/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.stacktips;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.ui.render.MenuFrame;
import sbs.modid.client.ui.render.SlotDecorator;

/**
 * Item Stack Tips over an open container's slots. Menu-bound kinds (skill level, collection tier)
 * are read only from the menu's own slots, never from the player's inventory below it.
 *
 * <p>Ordered just after {@code item_overlays} (50): a tip says what the item <i>is</i>, like the book
 * label, so every highlight that says what to <i>do</i> lands on top of it.
 */
public final class StackTipDecorator implements SlotDecorator {

    @Override
    public String id() {
        return "item_stack_tips";
    }

    @Override
    public int order() {
        return 60;
    }

    @Override
    public void decorate(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g, int mouseX, int mouseY) {
        SBSConfig.ItemOverlaySettings cfg = StackTips.cfg();
        if (!StackTips.scope(cfg).containers() || !StackTips.anyEnabled(cfg)) {
            return;
        }
        String title = StackTips.anyMenuKindEnabled(cfg) ? MenuFrame.of(screen).normalised() : null;
        for (Slot slot : screen.getMenu().slots) {
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            StackTipParser.Tip tip = StackTips.itemTip(stack);
            if (tip == null && title != null && !(slot.container instanceof Inventory)) {
                tip = StackTips.menuTip(stack, title);
            }
            StackTips.draw(g, cfg, tip, slot.x, slot.y);
        }
    }
}
