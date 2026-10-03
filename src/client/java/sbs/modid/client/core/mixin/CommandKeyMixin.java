/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.core.keybind.KeybindDispatch;

/**
 * Feeds raw keyboard presses to {@link KeybindDispatch} – user
 * {@link sbs.modid.client.core.keybind.CommandKeybind}s and every feature hotkey.
 *
 * <p>Injects at the head of {@code KeyboardHandler.keyPress(long, int, KeyEvent)} – the single GLFW
 * key callback the client funnels every key event through. Only a fresh press ({@code GLFW_PRESS})
 * is dispatched; the in-world / no-screen gate and the fan-out itself live in the dispatcher, which
 * {@code CommandMouseMixin} shares so a mouse button behaves exactly like a key. The injection is
 * purely additive: it never cancels, so vanilla key handling is untouched.
 */
@Mixin(KeyboardHandler.class)
public class CommandKeyMixin {

    /** GLFW {@code GLFW_PRESS}; only a fresh press (not release/repeat) triggers a keybind. */
    private static final int GLFW_PRESS = 1;

    @Inject(method = "keyPress", at = @At("HEAD"))
    private void skyblockSimplified$runCommandKeybinds(long window, int action, KeyEvent event, CallbackInfo ci) {
        if (action != GLFW_PRESS) {
            return;
        }
        KeybindDispatch.press(event.key(), event.modifiers());
    }
}
