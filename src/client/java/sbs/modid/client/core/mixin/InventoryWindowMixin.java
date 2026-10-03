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
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.helper.inventory.ui.InventoryWindow;

/**
 * Inventory Window placement: writes the window's position into a container screen after its
 * {@code init} (subclass code included, which is why this is the outer {@code init(int, int)} and not
 * the container's own) and again at the start of every frame, before the background pass draws the
 * panel. Vanilla rewrites {@code leftPos} on a resize and on every recipe-book toggle; reapplying
 * here is what keeps the window where the player put it. Does nothing for non-container screens or
 * while the feature is off.
 */
@Mixin(Screen.class)
public abstract class InventoryWindowMixin {

    @Inject(method = "init(II)V", at = @At("TAIL"))
    private void skyblockSimplified$inventoryWindowInit(int width, int height, CallbackInfo ci) {
        InventoryWindow.apply((Screen) (Object) this);
    }

    @Inject(method = "extractRenderStateWithTooltipAndSubtitles", at = @At("HEAD"))
    private void skyblockSimplified$inventoryWindowFrame(GuiGraphicsExtractor g, int mouseX, int mouseY,
                                                         float a, CallbackInfo ci) {
        InventoryWindow.apply((Screen) (Object) this);
    }
}
