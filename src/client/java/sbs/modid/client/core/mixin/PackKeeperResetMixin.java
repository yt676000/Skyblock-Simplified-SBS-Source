/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.resources.server.DownloadedPackSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.helper.texture.logic.HypixelPackKeeper;

/**
 * Vanilla drops every server pack on disconnect; {@link HypixelPackKeeper}'s per-connection record of
 * pack ids and of packs vanilla still holds goes with them.
 */
@Mixin(DownloadedPackSource.class)
public abstract class PackKeeperResetMixin {

    @Inject(method = "cleanupAfterDisconnect", at = @At("HEAD"))
    private void skyblockSimplified$resetKeeper(CallbackInfo ci) {
        HypixelPackKeeper.reset();
    }
}
