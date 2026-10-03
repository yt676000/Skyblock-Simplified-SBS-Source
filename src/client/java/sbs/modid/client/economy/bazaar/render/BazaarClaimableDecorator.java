/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.bazaar.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.economy.bazaar.logic.BazaarOrderTracker;
import sbs.modid.client.helper.visual.render.ChromaBorder;
import sbs.modid.client.ui.render.MenuFrame;
import sbs.modid.client.ui.render.RenderTier;
import sbs.modid.client.ui.render.SlotDecorator;

/**
 * The animated chroma border around every slot whose item is claimable.
 *
 * <p>Drawn after the plain order-status wash so a claimable order reads as claimable rather than as
 * whatever its status was a moment before it filled - the two decorations are deliberately ordered
 * against each other, which the five competing mixins this replaced could not express.
 *
 * <p>Reads the lore component directly and never touches vanilla tooltips or slot behaviour.
 *
 * <p><b>Bazaar screens only, and that gate is not cosmetic.</b> This shipped without one: on every
 * container in the game, on every frame, it read the lore of every slot and ran a regex over every
 * line of it - a full chest is several hundred string builds and several hundred matches, sixty
 * times a second, to decorate nothing. It is on by default, so every player paid it in every menu
 * they opened. The test is deliberately the broad {@code isBazaarGui} rather than "the orders menu":
 * a claimable slot is only expected there, but being wrong about which Bazaar page can show one
 * costs the player the decoration, while being broad only costs a screen the mod was already
 * drawing on.
 */
public final class BazaarClaimableDecorator implements SlotDecorator {

    @Override
    public String id() {
        return "bazaar_claimable";
    }

    @Override
    public int order() {
        return 400;
    }

    @Override
    public RenderTier tier(MenuFrame frame) {
        return RenderTier.when(isBazaar(frame));
    }

    @Override
    public void decorate(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g,
                         int mouseX, int mouseY) {
        if (!ConfigManager.getInstance().get().bazaar.highlightClaimable
                || !isBazaar(MenuFrame.of(screen))) {
            return;
        }
        AbstractContainerMenu menu = screen.getMenu();
        int slotCount = menu.getItems().size();
        for (int index = 0; index < slotCount; index++) {
            Slot slot = menu.getSlot(index);
            // Chroma is drawn for both partially-filled (State 2) and fully-filled (State 3) slots.
            if (BazaarChroma.detect(slot.getItem()).showsChroma()) {
                ChromaBorder.draw(g, slot.x, slot.y);
            }
        }
    }

    /** The one screen test, asked by both the tier and the draw so the two cannot disagree. */
    private static boolean isBazaar(MenuFrame frame) {
        return frame != null && BazaarOrderTracker.isBazaarGui(frame.normalised());
    }
}
