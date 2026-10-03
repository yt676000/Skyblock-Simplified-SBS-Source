/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.inventorybuttons.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import sbs.modid.client.helper.inventorybuttons.logic.InventoryButton;
import sbs.modid.client.helper.inventorybuttons.logic.InventoryButtons;
import sbs.modid.client.helper.inventorybuttons.render.InventoryButtonRenderer;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.core.mixin.AbstractContainerScreenAccessor;

import java.util.Optional;

/**
 * The live half of the Inventory Buttons module: the buttons as they appear over a real, open
 * container, and the clicks that fire them.
 *
 * <p>Buttons are positioned relative to the container GUI's top-left, so they follow the inventory
 * wherever it is centred; this class is the only place that adds the GUI origin.
 *
 * <p>Rendering is driven from {@code OverlayRenderMixin} and input from
 * {@code InventoryButtonsInputMixin}, matching how the Recipe Viewer overlay is wired.
 */
public final class InventoryButtonsOverlay {

    private InventoryButtonsOverlay() {
    }

    /** Called from the overlay render hook, in absolute screen coordinates. */
    public static void render(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g,
                              int mouseX, int mouseY) {
        if (!InventoryButtons.enabled() || InventoryButtons.all().isEmpty()) {
            return;
        }
        AbstractContainerScreenAccessor bounds = (AbstractContainerScreenAccessor) screen;
        int left = bounds.skyblockSimplified$leftPos();
        int top = bounds.skyblockSimplified$topPos();

        InventoryButton hovered = null;
        for (InventoryButton button : InventoryButtons.all()) {
            int x = left + button.x;
            int y = top + button.y;
            boolean over = mouseX >= x && mouseX < x + button.width()
                    && mouseY >= y && mouseY < y + button.height();
            InventoryButtonRenderer.draw(g, button, x, y, over, false, false);
            if (over) {
                hovered = button;
            }
        }
        if (hovered != null) {
            g.setTooltipForNextFrame(Minecraft.getInstance().font,
                    InventoryButtonRenderer.tooltip(hovered, false), Optional.empty(),
                    mouseX, mouseY, SBSTheme.tooltipStyle());
        }
    }

    /**
     * Handles a click on the container screen.
     *
     * @return {@code true} when a button consumed it, so the click never reaches the slot behind it
     */
    public static boolean handleClick(AbstractContainerScreen<?> screen, MouseButtonEvent event) {
        if (!InventoryButtons.enabled() || InventoryButtons.all().isEmpty() || event.button() != 0) {
            return false;
        }
        AbstractContainerScreenAccessor bounds = (AbstractContainerScreenAccessor) screen;
        double relX = event.x() - bounds.skyblockSimplified$leftPos();
        double relY = event.y() - bounds.skyblockSimplified$topPos();

        InventoryButton button = InventoryButtons.at(relX, relY);
        if (button == null) {
            return false;
        }
        InventoryButtons.run(button);
        return true;
    }
}
