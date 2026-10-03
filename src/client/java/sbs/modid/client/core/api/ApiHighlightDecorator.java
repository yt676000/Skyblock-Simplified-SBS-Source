/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.api;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import sbs.modid.client.ui.render.SlotDecorations;
import sbs.modid.client.ui.render.SlotDecorator;

import java.util.Set;

/**
 * Draws the slot highlights the local API asked for, on whatever container is open.
 *
 * <p>Generic over every container - chest, player inventory, merchant, anvil - because the caller is
 * an external tool naming slot indices, and it has no way to know which screen the player will be
 * looking at.
 *
 * <p><b>Fixed on the way through:</b> this decoration previously added {@code leftPos}/{@code topPos}
 * to the slot's coordinates, which are already GUI-relative by the time {@code extractSlots} runs -
 * so every API-requested highlight was drawn a full GUI origin away from the slot it named. The
 * other four decorations of the era did it correctly; this one carried a comment asserting it ran in
 * absolute space, which vanilla's own {@code extractContents} disproves in three instructions.
 *
 * <p>The highlight set is an immutable snapshot published by the HTTP thread, so reading it on the
 * render thread needs no lock.
 */
public final class ApiHighlightDecorator implements SlotDecorator {

    @Override
    public String id() {
        return "api_highlights";
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public void decorate(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g,
                         int mouseX, int mouseY) {
        Set<Integer> highlighted = HighlightManager.getInstance().getHighlighted();
        if (highlighted.isEmpty()) {
            return;
        }
        AbstractContainerMenu menu = screen.getMenu();
        int slotCount = menu.getItems().size();
        for (int index : highlighted) {
            if (index < 0 || index >= slotCount) {
                continue;
            }
            SlotDecorations.box(g, menu.getSlot(index),
                    SlotDecorations.HIGHLIGHT_FILL, SlotDecorations.HIGHLIGHT_FRAME);
        }
    }
}
