/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.EffectsInInventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sbs.modid.client.core.config.ConfigManager;

/**
 * Extends the GUI module's existing "Hide Potion Effect Status" toggle to the inventory: vanilla
 * renders the active potion effects beside every container screen via {@link EffectsInInventory},
 * which overlaps the space the Recipe Viewer panel uses.
 *
 * <p>While the toggle is on, both the render entry point ({@code extractRenderState}) and the
 * visibility query ({@code canSeeEffects}) are suppressed – so the panel is hidden <i>and</i> any
 * layout logic that asks about it treats it as absent, freeing the side of the inventory. The
 * effects themselves keep working; only the visual overlay is hidden. Turning the setting off
 * restores vanilla rendering untouched.
 */
@Mixin(EffectsInInventory.class)
public class InventoryEffectsMixin {

    private static boolean sbs$hidden() {
        return ConfigManager.getInstance().get().hypixelGui.hidePotionEffects;
    }

    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$hideInventoryEffects(GuiGraphicsExtractor g, int mouseX, int mouseY,
                                                         CallbackInfo ci) {
        if (sbs$hidden()) {
            ci.cancel();
        }
    }

    @Inject(method = "canSeeEffects", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$noVisibleEffects(CallbackInfoReturnable<Boolean> cir) {
        if (sbs$hidden()) {
            cir.setReturnValue(false);
        }
    }
}
