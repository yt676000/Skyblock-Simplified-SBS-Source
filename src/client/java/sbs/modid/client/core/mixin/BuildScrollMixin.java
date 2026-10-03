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
import sbs.modid.client.core.api.ScreenAccess;
import sbs.modid.client.helper.build.logic.BuildKeys;

/**
 * The mouse wheel while a Build Tools hologram is placed (raise / lower it) or the build guide is on
 * (change layer). Cancels the hotbar scroll only in those states, with no screen open.
 */
@Mixin(MouseHandler.class)
public abstract class BuildScrollMixin {

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$buildScroll(long window, double xOffset, double yOffset, CallbackInfo ci) {
        if (yOffset == 0 || ScreenAccess.current() != null) {
            return;
        }
        if (BuildKeys.onScroll(yOffset)) {
            ci.cancel();
        }
    }
}
