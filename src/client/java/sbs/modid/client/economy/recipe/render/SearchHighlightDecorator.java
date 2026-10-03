/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.recipe.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import sbs.modid.client.economy.recipe.logic.SearchHighlightState;
import sbs.modid.client.ui.render.SlotDecorations;
import sbs.modid.client.ui.render.SlotDecorator;

/**
 * Recipe Viewer search highlighting: every visible slot whose item matches the current query.
 *
 * <p>Deliberately the same wash as the API's own highlights - both answer "the slot you asked
 * about", and giving them different colours would imply a distinction that does not exist.
 */
public final class SearchHighlightDecorator implements SlotDecorator {

    @Override
    public String id() {
        return "recipe_search";
    }

    @Override
    public int order() {
        return 200;
    }

    @Override
    public void decorate(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g,
                         int mouseX, int mouseY) {
        SearchHighlightState state = SearchHighlightState.getInstance();
        if (!state.isEnabled()) {
            return;
        }
        AbstractContainerMenu menu = screen.getMenu();
        int slotCount = menu.getItems().size();
        for (int index = 0; index < slotCount; index++) {
            Slot slot = menu.getSlot(index);
            if (state.matches(slot.getItem())) {
                SlotDecorations.box(g, slot, SlotDecorations.HIGHLIGHT_FILL,
                        SlotDecorations.HIGHLIGHT_FRAME);
            }
        }
    }
}
