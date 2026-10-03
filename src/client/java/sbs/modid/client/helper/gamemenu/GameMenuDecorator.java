/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */
package sbs.modid.client.helper.gamemenu;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.helper.visual.render.ChromaBorder;
import sbs.modid.client.ui.render.MenuFrame;
import sbs.modid.client.ui.render.RenderTier;
import sbs.modid.client.ui.render.SlotDecorator;

/**
 * Frames the SkyBlock button in the lobby's {@code Game Menu} with the same chroma border as a
 * claimable Bazaar order, so it is the first thing the eye lands on among twenty-odd games.
 *
 * <p>Gated on the exact title, so every other container pays one cached string compare and nothing
 * else.
 */
public final class GameMenuDecorator implements SlotDecorator {

    @Override
    public String id() {
        return "game_menu_skyblock";
    }

    @Override
    public int order() {
        return 410; // its own menu, so nothing to stack against - distinct only because orders must be
    }

    @Override
    public RenderTier tier(MenuFrame frame) {
        return RenderTier.when(isGameMenu(frame));
    }

    @Override
    public void decorate(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g,
                         int mouseX, int mouseY) {
        if (!ConfigManager.getInstance().get().convenience.highlightGameMenuSkyBlock
                || !isGameMenu(MenuFrame.of(screen))) {
            return;
        }
        AbstractContainerMenu menu = screen.getMenu();
        int slotCount = menu.getItems().size();
        for (int index = 0; index < slotCount; index++) {
            Slot slot = menu.getSlot(index);
            if (GameMenuSkyBlock.isSkyBlockButton(slot.getItem())) {
                ChromaBorder.draw(g, slot.x, slot.y);
            }
        }
    }

    /** The one screen test, asked by both the tier and the draw so the two cannot disagree. */
    private static boolean isGameMenu(MenuFrame frame) {
        return frame != null && GameMenuSkyBlock.TITLE.equals(frame.normalised());
    }
}
