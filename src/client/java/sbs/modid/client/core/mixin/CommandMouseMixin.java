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
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.core.keybind.KeybindDispatch;
import sbs.modid.client.core.keybind.Keys;

/**
 * The mouse half of the keybind input path: a mouse button or a wheel notch fires a bound hotkey
 * exactly as a key does.
 *
 * <p>{@code MouseHandler.onButton} and {@code onScroll} are the two GLFW callbacks every mouse event
 * enters the game through – the same pair vanilla builds its own mouse keybinds and the hotbar wheel
 * out of. Taking them at the head and handing the encoded {@link Keys} code to
 * {@link KeybindDispatch} is all a mouse bind needs: the dispatcher already gates on in-world /
 * no-screen and matches the code against every feature's config field.
 *
 * <p><b>Nothing is cancelled.</b> A hotkey on Mouse Left still swings, and one on the wheel still
 * changes the hotbar slot – the mod adds to the player's input and never eats it. That is also why
 * the release is ignored: one press is one fire.
 */
@Mixin(MouseHandler.class)
public abstract class CommandMouseMixin {

    /** GLFW {@code GLFW_PRESS}; a release must not fire the keybind a second time. */
    private static final int GLFW_PRESS = 1;

    @Inject(method = "onButton", at = @At("HEAD"))
    private void skyblockSimplified$mouseKeybinds(long window, MouseButtonInfo button, int action,
                                                  CallbackInfo ci) {
        if (action != GLFW_PRESS || !skyblockSimplified$isGameWindow(window)) {
            return;
        }
        // The Overlay Inspector's pointer owns every button while it is up (it swallows the click so
        // it cannot also swing), so a hotkey must not fire behind it. Its own toggle still works:
        // OverlayInspector.onClick answers a mouse-bound toggle before it swallows anything.
        if (sbs.modid.client.helper.overlayinspector.OverlayInspector.getInstance().isActive()) {
            return;
        }
        KeybindDispatch.press(Keys.ofMouseButton(button.button()), button.modifiers());
    }

    @Inject(method = "onScroll", at = @At("HEAD"))
    private void skyblockSimplified$wheelKeybinds(long window, double xOffset, double yOffset,
                                                  CallbackInfo ci) {
        if (yOffset == 0 || !skyblockSimplified$isGameWindow(window)) {
            return;
        }
        // The dispatcher counts whole notches itself, so a fractional-scroll device fires a bind
        // once per turn. The modifiers come from the keyboard because GLFW's scroll callback carries
        // none – without that, "Ctrl + Wheel Up" could never match.
        KeybindDispatch.wheel(yOffset, Keys.heldModifiers());
    }

    /**
     * Both hooks sit above vanilla's own {@code handle == window.handle()} check, so they have to
     * repeat it: an event from another window is not the player pressing anything in game.
     */
    private static boolean skyblockSimplified$isGameWindow(long window) {
        return window == Minecraft.getInstance().getWindow().handle();
    }
}
