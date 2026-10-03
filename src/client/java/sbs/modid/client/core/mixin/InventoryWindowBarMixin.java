/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.helper.inventory.ui.InventoryWindow;

/**
 * Draws the Inventory Window title bar at the end of {@code extractContents}: the pose is back in
 * absolute coordinates there, the panel is already down, and the floating windows (drawn later, from
 * the end of the frame) still land on top of it like every other window.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class InventoryWindowBarMixin {

    @Inject(method = "extractContents", at = @At("TAIL"))
    private void skyblockSimplified$inventoryWindowBar(GuiGraphicsExtractor g, int mouseX, int mouseY,
                                                       float a, CallbackInfo ci) {
        InventoryWindow.render((AbstractContainerScreen<?>) (Object) this, g, mouseX, mouseY);
    }
}
