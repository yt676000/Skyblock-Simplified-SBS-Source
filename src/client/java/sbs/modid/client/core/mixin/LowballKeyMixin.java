/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.world.inventory.Slot;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sbs.modid.client.economy.lowball.logic.Lowball;
import sbs.modid.client.economy.recipe.logic.SearchHighlightState;

/**
 * Lowball's keys inside containers (developer mode only): appraise the hovered slot, or the other
 * side of the trade window. Never steals a key from a focused text field. The render hook only
 * compares the hovered slot, so the tooltip lines go away once the slot is left.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class LowballKeyMixin {

    @Shadow
    protected Slot hoveredSlot;

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$lowballKey(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (!Lowball.active()) {
            return;
        }
        var window = sbs.modid.client.economy.lowball.ui.LowballWindow.getInstance();
        if (event.key() == GLFW.GLFW_KEY_ESCAPE && window.isOpen()) {
            window.close();   // Esc closes the Lowball window first, the container on the next press
            cir.setReturnValue(true);
            return;
        }
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        if (self.getFocused() instanceof EditBox || SearchHighlightState.getInstance().isSearchFocused()) {
            return;
        }
        boolean shift = (event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0;
        if (Lowball.onContainerKey(self, this.hoveredSlot, event.key(), shift)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "extractTooltip", at = @At("HEAD"))
    private void skyblockSimplified$lowballHover(GuiGraphicsExtractor g, int mouseX, int mouseY, CallbackInfo ci) {
        Lowball.onHover(this.hoveredSlot);
    }
}
