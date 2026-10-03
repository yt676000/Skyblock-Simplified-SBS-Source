/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.ping.ClientboundPongResponsePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.ui.hud.logic.ServerStatsTracker;

/**
 * Feeds pong responses to the {@link ServerStatsTracker}'s real round-trip ping measurement
 * (the tracker sends {@code ServerboundPingRequestPacket}s and matches the echoed payload, so
 * vanilla's own debug pings are ignored).
 */
@Mixin(ClientPacketListener.class)
public abstract class PongResponseMixin {

    @Inject(method = "handlePongResponse", at = @At("TAIL"))
    private void skyblockSimplified$onPong(ClientboundPongResponsePacket packet, CallbackInfo ci) {
        ServerStatsTracker.getInstance().onPong(packet.time());
    }
}
