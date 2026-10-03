/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.helper.inventory.ui.InventoryOverlay;

/**
 * The screen half of the Inventory Overlay: strips the backdrop from the player's inventory so the
 * game stays visible while the inventory is used.
 *
 * <p>{@code Screen.extractBackground} dispatches to one of three backdrop passes depending on the
 * screen ({@code extractBlurredBackground} + {@code extractMenuBackground}, or
 * {@code extractTransparentBackground} for in-game UI). Cancelling those three – rather than
 * {@code extractBackground} itself – removes only the blur and the dimming while leaving everything
 * the background pass draws afterwards (the container texture, the player preview) intact.
 *
 * <p>Scoped hard to {@link InventoryScreen}: every other screen, including chests and SkyBlock
 * menus, keeps its normal backdrop. Items are interacted with exactly as in vanilla – this changes
 * nothing but what is painted.
 */
@Mixin(Screen.class)
public abstract class TransparentInventoryMixin {

    /** Whether this screen is the player inventory AND the transparent mode is switched on. */
    private boolean skyblockSimplified$transparent() {
        return (Object) this instanceof InventoryScreen && InventoryOverlay.screenActive();
    }

    @Inject(method = "extractBlurredBackground", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$noBlur(GuiGraphicsExtractor g, CallbackInfo ci) {
        if (skyblockSimplified$transparent()) {
            ci.cancel();
        }
    }

    @Inject(method = "extractMenuBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V",
            at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$noMenuBackground(GuiGraphicsExtractor g, CallbackInfo ci) {
        if (skyblockSimplified$transparent()) {
            ci.cancel();
        }
    }

    @Inject(method = "extractTransparentBackground", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$noDimming(GuiGraphicsExtractor g, CallbackInfo ci) {
        if (skyblockSimplified$transparent()) {
            ci.cancel();
        }
    }
}
