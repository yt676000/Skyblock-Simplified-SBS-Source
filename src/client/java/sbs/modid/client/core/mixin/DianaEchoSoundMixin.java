/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The sound packet, for the Diana toolkit: the Echo trail plays a harp note per point, and the
 * note's pitch is how far away the burrow is (captured 2026-10-04; see {@code SpadeGuess}).
 *
 * <p>The packet rather than the played sound, because the pitch and position are the server's here,
 * before the sound options or another mod can mute or drop the sound. Injected just after
 * {@code PacketUtils.ensureRunningOnSameThread}, which only the client-thread pass reaches - the
 * same point Diana Log Mode uses, checked with {@code javap} against the 26.2 jar.
 *
 * <p>Fenced by {@code DianaGuard}; nothing is cancelled or changed.
 */
@Mixin(ClientPacketListener.class)
public class DianaEchoSoundMixin {

    @Inject(method = "handleSoundEvent", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread("
                    + "Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;"
                    + "Lnet/minecraft/network/PacketProcessor;)V",
            shift = At.Shift.AFTER))
    private void skyblockSimplified$dianaEchoNote(ClientboundSoundPacket packet, CallbackInfo ci) {
        sbs.modid.client.combat.diana.logic.DianaGuard.run(sbs.modid.client.combat.diana.logic.DianaGuard.Hook.SOUND,
                packet, () -> sbs.modid.client.combat.diana.logic.BurrowDetector.getInstance().onSoundPacket(packet));
    }
}
