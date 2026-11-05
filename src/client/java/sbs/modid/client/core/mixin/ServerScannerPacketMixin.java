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
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.core.dev.scanner.ServerScanner;

/**
 * Server Scanner (developer tool): the packets a full capture records that no other hook already
 * hands over - particles, titles, item pickups and respawns.
 *
 * <p><b>Injected after {@code PacketUtils.ensureRunningOnSameThread}</b>, the same point and for the
 * same reason as {@link DianaDevLogMixin}: reached once per packet, on the client thread only, and
 * before vanilla applies it - which for a pickup means the ground item still exists to be described.
 *
 * <p><b>Observation only, one boolean read while idle.</b> Each injection reads the scanner's flag
 * for that hook, true only while a session records its channel, and returns. {@code require = 0}: a
 * target a Minecraft update moves costs that one event type in a dev capture, never a crash on join.
 * All five targets checked with {@code javap} against the 26.2 jar.
 */
@Mixin(ClientPacketListener.class)
public class ServerScannerPacketMixin {

    private static final String ENSURE = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread("
            + "Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;"
            + "Lnet/minecraft/network/PacketProcessor;)V";

    @Inject(method = "handleParticleEvent", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER), require = 0)
    private void skyblockSimplified$scanParticles(ClientboundLevelParticlesPacket packet, CallbackInfo ci) {
        if (ServerScanner.PARTICLES) {
            ServerScanner.onParticles(packet);
        }
    }

    @Inject(method = "setTitleText", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER), require = 0)
    private void skyblockSimplified$scanTitle(ClientboundSetTitleTextPacket packet, CallbackInfo ci) {
        if (ServerScanner.TITLES) {
            ServerScanner.onTitle(packet.text(), false);
        }
    }

    @Inject(method = "setSubtitleText", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER), require = 0)
    private void skyblockSimplified$scanSubtitle(ClientboundSetSubtitleTextPacket packet, CallbackInfo ci) {
        if (ServerScanner.TITLES) {
            ServerScanner.onTitle(packet.text(), true);
        }
    }

    @Inject(method = "handleTakeItemEntity", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER), require = 0)
    private void skyblockSimplified$scanPickup(ClientboundTakeItemEntityPacket packet, CallbackInfo ci) {
        if (ServerScanner.PICKUPS) {
            ServerScanner.onTakeItem(packet);
        }
    }

    @Inject(method = "handleRespawn", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER), require = 0)
    private void skyblockSimplified$scanRespawn(ClientboundRespawnPacket packet, CallbackInfo ci) {
        if (ServerScanner.RESPAWNS) {
            ServerScanner.onRespawn();
        }
    }
}
