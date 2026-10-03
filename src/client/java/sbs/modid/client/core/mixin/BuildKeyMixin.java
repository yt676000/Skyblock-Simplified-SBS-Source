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
import sbs.modid.client.core.api.ScreenAccess;
import sbs.modid.client.helper.build.logic.BuildKeys;

/**
 * Build Tools' in-world keys while a hologram is being placed or an edit preview waits: arrows,
 * Page Up/Down, R, F, G, Enter and Escape.
 *
 * <p>Cancels only when {@link BuildKeys} consumed the key, which it does only in those two states and
 * only with no screen open - so typing in chat, any menu, and the game's own use of these keys the
 * rest of the time are untouched. Presses and repeats both count, so a held arrow keeps nudging.
 */
@Mixin(KeyboardHandler.class)
public class BuildKeyMixin {

    private static final int GLFW_PRESS = 1;
    private static final int GLFW_REPEAT = 2;

    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$buildKeys(long window, int action, KeyEvent event, CallbackInfo ci) {
        if ((action != GLFW_PRESS && action != GLFW_REPEAT) || ScreenAccess.current() != null) {
            return;
        }
        if (BuildKeys.onKey(event.key(), event.modifiers(), action == GLFW_REPEAT)) {
            ci.cancel();
        }
    }
}
