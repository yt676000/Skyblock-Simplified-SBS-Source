/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sbs.modid.client.helper.inventorybuttons.ui.InventoryButtonsOverlay;

/**
 * Routes clicks on the Inventory Buttons overlay.
 *
 * <p>Injects at HEAD and cancels only when a button was actually hit, so the click becomes the
 * button's command instead of a slot interaction underneath it. Every other click falls through to
 * vanilla (and to the other overlays) untouched, and with the module off this never consumes
 * anything at all.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class InventoryButtonsInputMixin {

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$inventoryButtonClick(MouseButtonEvent event, boolean doubled,
                                                         CallbackInfoReturnable<Boolean> cir) {
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        // The Garden visitor's Bazaar buttons ride the same click path: they sit beside the menu,
        // never over a slot, so a hit here is never a slot click that got taken away.
        if (sbs.modid.client.skills.garden.ui.VisitorBazaarButtons.getInstance().handleClick(self, event)) {
            cir.setReturnValue(true);
            return;
        }
        if (InventoryButtonsOverlay.handleClick(self, event)) {
            cir.setReturnValue(true);
        }
    }
}
