/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.croesus.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import sbs.modid.client.ui.render.MenuFrame;
import sbs.modid.client.ui.render.RenderTier;
import sbs.modid.client.ui.render.SlotDecorator;

/**
 * Registers the Croesus run marks. Everything they mean, and all the gating, lives in
 * {@link CroesusOverlay}.
 *
 * <p>Ordered below the chest marks: the two can meet on one screen if Hypixel ever lists a run and
 * its chests together, and a run's wash belongs under a chest's outline rather than over it.
 */
public final class CroesusDecorator implements SlotDecorator {

    @Override
    public String id() {
        return "croesus_runs";
    }

    @Override
    public int order() {
        return 690;
    }

    @Override
    public RenderTier tier(MenuFrame frame) {
        // The same test decorate() gates on, asked of the same cached frame - never a second copy.
        return RenderTier.when(frame == null || CroesusOverlay.isCroesus(frame));
    }

    @Override
    public void decorate(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g,
                         int mouseX, int mouseY) {
        CroesusOverlay.render(screen, g);
    }
}
