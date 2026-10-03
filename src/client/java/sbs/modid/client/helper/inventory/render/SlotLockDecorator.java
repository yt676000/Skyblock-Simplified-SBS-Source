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
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.helper.inventory.logic.SlotLock;
import sbs.modid.client.ui.render.SlotDecorator;

/**
 * A padlock over every locked slot.
 *
 * <p>Drawn last of all the decorations, because it is the only one that has to stay readable on top
 * of whatever else decided to colour the same slot - a lock hidden under a Bazaar status wash is a
 * lock the player does not know is there, and finding out costs them the click they were protected
 * from.
 *
 * <p>Toggling a lock is a click, not a decoration, and stays in {@code SlotLockInputMixin}.
 */
public final class SlotLockDecorator implements SlotDecorator {

    /** Diagnostic: last container menu whose locked-slot geometry was logged (log once per open). */
    private static Object lastLoggedMenu;

    @Override
    public String id() {
        return "slot_lock";
    }

    @Override
    public int order() {
        return 900;
    }

    @Override
    public void decorate(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g,
                         int mouseX, int mouseY) {
        if (!SlotLock.enabled()) {
            return;
        }
        AbstractContainerMenu menu = screen.getMenu();
        boolean log = menu != lastLoggedMenu;
        if (log) {
            lastLoggedMenu = menu;
            var bounds = (sbs.modid.client.core.mixin.AbstractContainerScreenAccessor) screen;
            SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][SlotLock] screen={} leftPos={} topPos={} slots={}",
                    screen.getClass().getSimpleName(), bounds.skyblockSimplified$leftPos(),
                    bounds.skyblockSimplified$topPos(), menu.slots.size());
        }
        for (Slot slot : menu.slots) {
            if (SlotLock.isLocked(slot)) {
                if (log) {
                    SkyblockSimplifiedSBS.LOGGER.info(
                            "[SBS][SlotLock] locked invIndex={} slot.x={} slot.y={} (container={})",
                            SlotLock.inventoryIndexOf(slot), slot.x, slot.y,
                            slot.container.getClass().getSimpleName());
                }
                SlotLockIcon.draw(g, slot.x, slot.y);
            }
        }
    }
}
