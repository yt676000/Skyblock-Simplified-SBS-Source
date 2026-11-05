/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.helper.particles.ParticleLimiter;

/**
 * Brackets the expansion of one server particle packet so the spam limiter knows which packet the
 * particles reaching {@code createParticle} came from - the count, speed and spread a feature
 * signature needs are only on the packet. See {@link ParticleLimiter}.
 *
 * <p>Injected just after {@code ensureRunningOnSameThread}: on the netty thread that call throws to
 * re-dispatch, so the begin only ever runs on the main-thread invocation, before the spawn loop.
 */
@Mixin(ClientPacketListener.class)
public class ParticleLimiterPacketMixin {

    private static final String ENSURE = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread("
            + "Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;"
            + "Lnet/minecraft/network/PacketProcessor;)V";

    @Inject(method = "handleParticleEvent", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER))
    private void skyblockSimplified$beginParticlePacket(ClientboundLevelParticlesPacket packet, CallbackInfo ci) {
        ParticleLimiter.beginPacket(packet);
    }

    @Inject(method = "handleParticleEvent", at = @At("TAIL"))
    private void skyblockSimplified$endParticlePacket(ClientboundLevelParticlesPacket packet, CallbackInfo ci) {
        ParticleLimiter.endPacket();
    }
}
