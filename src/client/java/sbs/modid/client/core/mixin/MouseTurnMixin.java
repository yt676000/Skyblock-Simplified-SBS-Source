/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.skills.farming.logic.MouseLock;

/**
 * Farming module's Mouse Lock: cancels {@code MouseHandler.turnPlayer} (the single method that
 * applies accumulated mouse movement to the player's camera) while the lock is active, so the
 * view cannot drift during farming runs. Everything else about the mouse (buttons, GUIs,
 * hotbar scrolling) is untouched.
 */
@Mixin(MouseHandler.class)
public abstract class MouseTurnMixin {

    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$mouseLock(double movementTime, CallbackInfo ci) {
        if (MouseLock.isActive()) {
            ci.cancel();
        }
    }
}
