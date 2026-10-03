/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sbs.modid.client.core.keybind.Keys;
import sbs.modid.client.helper.slothotkey.logic.SlotHotkeyManager;

/**
 * Feeds container-screen key, mouse and wheel input to the {@link SlotHotkeyManager}: arming a scan,
 * capturing the clicked slot, and firing a matching hotkey. A lower priority than the overlay input
 * mixin ({@code ContainerSearchBarMixin}) so a scan capture / hotkey fire takes the click before it
 * reaches the container's own handling (or the item would be picked up on a scan click).
 */
@Mixin(value = AbstractContainerScreen.class, priority = 900)
public abstract class SlotHotkeyMixin {

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$slotHotkeyMouse(MouseButtonEvent event, boolean doubled,
                                                    CallbackInfoReturnable<Boolean> cir) {
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        if (SlotHotkeyManager.getInstance().handleMouse(self, event.button(), event.modifiers(),
                event.x(), event.y())) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$slotHotkeyScroll(double mouseX, double mouseY, double scrollX,
                                                     double scrollY,
                                                     CallbackInfoReturnable<Boolean> cir) {
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        // Modifiers come from the keyboard: GLFW's scroll callback carries none, so "Shift + Wheel
        // Up" could not be told from a plain wheel bind otherwise.
        if (SlotHotkeyManager.getInstance().handleScroll(self, scrollY, Keys.heldModifiers())) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$slotHotkeyKey(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        // Leap Menu: a class key clicks that class's teammate's slot while the Spirit Leap menu is open.
        if (sbs.modid.client.dungeons.run.ui.LeapMenu.getInstance().handleKey(self, event.key())) {
            cir.setReturnValue(true);
            return;
        }
        if (SlotHotkeyManager.getInstance().handleKey(self, event.key(), event.modifiers())) {
            cir.setReturnValue(true);
        }
    }
}
