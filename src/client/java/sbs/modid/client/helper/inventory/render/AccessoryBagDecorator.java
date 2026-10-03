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
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.helper.inventory.logic.AccessoryIndex;
import sbs.modid.client.ui.render.MenuFrame;
import sbs.modid.client.ui.render.RenderTier;
import sbs.modid.client.ui.render.SlotDecorator;

/**
 * The Accessory Bag duplicate highlights, and the page read into the accessory index on the way
 * past. All gating (feature on, screen is the Accessory Bag) lives in the two targets.
 *
 * <p>Indexing rides along here because this runs whenever a container is open, and it is
 * deliberately <b>not</b> gated on the highlight toggle: the index is what answers "what am I
 * missing", and letting a cosmetic setting quietly stop it filling would make that feature fail in a
 * way nobody could trace back.
 */
public final class AccessoryBagDecorator implements SlotDecorator {

    @Override
    public String id() {
        return "accessory_bag";
    }

    @Override
    public int order() {
        return 500;
    }

    @Override
    public RenderTier tier(MenuFrame frame) {
        return RenderTier.when(AccessoryIndex.isBagMenu(frame));
    }

    @Override
    public void decorate(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g,
                         int mouseX, int mouseY) {
        if (ConfigManager.getInstance().get().accessoryBag.trackOwned) {
            AccessoryIndex.getInstance().capture(screen);
        }
        AccessoryBagHighlight.render(screen, g);
    }
}
