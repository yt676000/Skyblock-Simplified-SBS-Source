/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.core.api.ScreenAccess;
import sbs.modid.client.core.keybind.CommandKeybind;
import sbs.modid.client.core.keybind.Keys;
import sbs.modid.client.ui.theme.ScreenOpacity;

/**
 * Ctrl + wheel / Ctrl + middle click over an open screen: that screen's own opacity. Taken before the
 * screen and before wheel keybinds (priority 900) and consumed only when a screen with a stable key
 * is open and Ctrl alone is held - plain scrolling, Shift/Alt combinations and the in-game wheel are
 * left exactly as they were. A display setting; nothing is sent anywhere.
 */
@Mixin(value = MouseHandler.class, priority = 900)
public abstract class ScreenOpacityInputMixin {

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void sbs$opacityWheel(long window, double xOffset, double yOffset, CallbackInfo ci) {
        var screen = ScreenAccess.current();
        if (yOffset == 0 || screen == null || window != Minecraft.getInstance().getWindow().handle()
                || Keys.heldModifiers() != CommandKeybind.MOD_CTRL) {
            return;
        }
        if (ScreenOpacity.scroll(screen, yOffset)) {
            ci.cancel();
        }
    }

    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void sbs$opacityReset(long window, MouseButtonInfo button, int action, CallbackInfo ci) {
        var screen = ScreenAccess.current();
        if (action != GLFW.GLFW_PRESS || screen == null || button.button() != GLFW.GLFW_MOUSE_BUTTON_MIDDLE
                || window != Minecraft.getInstance().getWindow().handle()
                || Keys.heldModifiers() != CommandKeybind.MOD_CTRL) {
            return;
        }
        if (ScreenOpacity.reset(screen)) {
            ci.cancel();
        }
    }
}
