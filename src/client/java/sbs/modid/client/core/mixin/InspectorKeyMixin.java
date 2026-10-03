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
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.helper.overlayinspector.OverlayInspector;

/**
 * ESC leaves the Overlay Inspector.
 *
 * <p>The inspector runs with no screen open, so ESC would otherwise go straight to the pause menu -
 * from a state the player reads as "a menu is already open". Consuming that one key while the
 * pointer is up makes it behave like the escape from any other overlay.
 *
 * <p>Only the escape press is consumed, and only while inspecting; every other key (movement, chat,
 * hotbar) keeps working, which is deliberate - the pointer is meant to be used while playing.
 */
@Mixin(KeyboardHandler.class)
public class InspectorKeyMixin {

    /** GLFW {@code GLFW_PRESS}; a release must pass through or the key would read as held. */
    private static final int SBS_PRESS = 1;

    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$inspectorEscape(long window, int action, KeyEvent event,
                                                    CallbackInfo ci) {
        if (action != SBS_PRESS || event.key() != GLFW.GLFW_KEY_ESCAPE) {
            return;
        }
        OverlayInspector inspector = OverlayInspector.getInstance();
        if (inspector.isActive()) {
            inspector.deactivate();
            ci.cancel();
        }
    }
}
