/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */
package sbs.modid.client.core.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.core.input.MouseKeeper;

/**
 * "Keep Mouse Position": marks the grab a SERVER close causes, so {@code KeepMousePositionMixin}
 * can hold it back until it knows whether the next menu follows.
 *
 * <p>Brackets the {@code clientSideCloseContainer} call rather than HEAD/RETURN: HEAD also runs on
 * the network thread before {@code ensureRunningOnSameThread} throws, which would leave the flag set.
 */
@Mixin(ClientPacketListener.class)
public abstract class KeepMouseCloseMixin {

    @Inject(method = "handleContainerClose", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;clientSideCloseContainer()V"))
    private void sbs$beforeServerClose(CallbackInfo ci) {
        MouseKeeper.serverClosing(true);
    }

    @Inject(method = "handleContainerClose", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;clientSideCloseContainer()V",
            shift = At.Shift.AFTER))
    private void sbs$afterServerClose(CallbackInfo ci) {
        MouseKeeper.serverClosing(false);
    }
}
