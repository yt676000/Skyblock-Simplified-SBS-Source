/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.bitsshop.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import sbs.modid.client.economy.bitsshop.BitsShop;
import sbs.modid.client.ui.render.MenuFrame;
import sbs.modid.client.ui.render.RenderTier;
import sbs.modid.client.ui.render.SlotDecorator;

/**
 * The Bits Shop best-deal highlights. All gating (feature on, this container is actually a shop
 * page) lives in {@link BitsShopHighlight}.
 */
public final class BitsShopDecorator implements SlotDecorator {

    @Override
    public String id() {
        return "bits_shop";
    }

    @Override
    public int order() {
        return 600;
    }

    @Override
    public RenderTier tier(MenuFrame frame) {
        // Deliberately the memo, not the test: see BitsShop.maybeShopPage. Asking the real question
        // here would pay the cost the tier exists to avoid.
        return RenderTier.when(frame == null || BitsShop.maybeShopPage(frame.screen()));
    }

    @Override
    public void decorate(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g,
                         int mouseX, int mouseY) {
        BitsShopHighlight.render(screen, g);
    }
}
