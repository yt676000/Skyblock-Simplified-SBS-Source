/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.core.world.WorldTransitions;

/**
 * A proxy transfer sends the client back into configuration, which clears the level. The handler
 * starts with the thread redirect, so TAIL only ever runs on the main thread.
 */
@Mixin(ClientPacketListener.class)
public abstract class ConfigurationStartMixin {

    @Inject(method = "handleConfigurationStart", at = @At("TAIL"))
    private void skyblockSimplified$onConfigurationStart(ClientboundStartConfigurationPacket packet, CallbackInfo ci) {
        WorldTransitions.fire(WorldTransitions.Transition.SERVER_SWITCH);
    }
}
