/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.garden.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import sbs.modid.client.skills.garden.logic.GardenMenuHelpers;
import sbs.modid.client.skills.garden.model.GardenPlotCatalog;
import sbs.modid.client.ui.render.MenuFrame;
import sbs.modid.client.ui.render.SlotDecorator;

/**
 * The numbers the Garden menus do not print themselves: SkyMart copper prices, plot compost costs,
 * milestone tiers and upgrade levels.
 *
 * <p>Learning the plot icons rides along here, while Configure Plots is open. It throttles itself
 * and ignores every other menu.
 *
 * <p><b>Fixed on the way through:</b> these labels were drawn at {@code leftPos + slot.x}, which by
 * the time {@code extractSlots} runs is the GUI origin counted twice - so every number sat a full
 * GUI's width and height away from the item it described, off the side of the menu. The gating and
 * the values were right the whole time; only the two additions were wrong.
 */
public final class GardenMenuDecorator implements SlotDecorator {

    @Override
    public String id() {
        return "garden_menus";
    }

    @Override
    public int order() {
        return 800;
    }

    @Override
    public void decorate(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g,
                         int mouseX, int mouseY) {
        String title = MenuFrame.of(screen).title();
        GardenPlotCatalog.getInstance().learn(screen.getMenu(), title);
        GardenMenuHelpers.draw(g, screen.getMenu(), title);
    }
}
