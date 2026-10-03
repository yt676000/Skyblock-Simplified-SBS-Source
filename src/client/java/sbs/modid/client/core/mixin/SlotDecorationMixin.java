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
import sbs.modid.client.ui.render.SlotDecorations;

/**
 * The mod's single hook for drawing over container slots.
 *
 * <p>Injects at the tail of {@code extractSlots} - after the slot items are drawn, before the cursor
 * item and tooltips - so every decoration sits between the two, which is where all five of the hooks
 * this replaced already wanted to be.
 *
 * <p>There is deliberately nothing else here. Which screens matter, what colour anything is and
 * whether a feature is switched on are all decided in {@link SlotDecorations} and the decorators it
 * loads; this class exists only so that the number of mixins competing for one vanilla method is
 * one. Adding a decoration must never mean touching this file again.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class SlotDecorationMixin {

    @Inject(method = "extractSlots", at = @At("TAIL"))
    private void skyblockSimplified$decorateSlots(GuiGraphicsExtractor g, int mouseX, int mouseY,
                                                  CallbackInfo ci) {
        SlotDecorations.render((AbstractContainerScreen<?>) (Object) this, g, mouseX, mouseY);
    }
}
