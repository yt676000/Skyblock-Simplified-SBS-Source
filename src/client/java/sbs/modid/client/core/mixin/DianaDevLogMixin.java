/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.combat.diana.devlog.DevLogEvents;
import sbs.modid.client.combat.diana.devlog.DianaDevLog;

/**
 * The server packets Diana Log Mode records, read on the client thread just before vanilla applies
 * them.
 *
 * <p><b>Injected after {@code PacketUtils.ensureRunningOnSameThread}, never at HEAD or TAIL.</b> Each
 * handler is entered twice: first on the netty thread, where that call schedules the packet for the
 * client thread and throws to abandon the netty pass, then on the client thread, where it returns.
 * HEAD would therefore fire on both threads; the point just after the call is reached only on the
 * client thread, exactly once per packet. TAIL would miss any handler that returns early, and it would
 * read the world after the packet had changed it - here the entity being removed still exists and the
 * block being replaced still has its old state, which is what the capture records.
 *
 * <p>Bundled packets need nothing extra: the bundle handler calls each sub-packet's handler on the
 * client thread, and each passes through the same point.
 *
 * <p><b>Observation only.</b> Nothing is cancelled, modified or sent, and every injection returns
 * nothing to vanilla. While the log is off each costs one static volatile read.
 *
 * <p><b>{@code require = 0} on every injection</b>, against this config's default. These hooks are
 * applied for every player whether or not a capture is ever started, so an injection point a
 * Minecraft update moves would otherwise crash everyone on joining a world - for a diagnostic. With
 * {@code require = 0} a moved target costs that one event type in a capture instead, and the gap is
 * visible there: {@code packet_counts} still counts the packets arriving. All 13 targets were checked
 * with {@code javap} against the 26.2 jar when this was written (one
 * {@code ensureRunningOnSameThread} call each).
 */
@Mixin(ClientPacketListener.class)
public class DianaDevLogMixin {

    private static final String ENSURE = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread("
            + "Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;"
            + "Lnet/minecraft/network/PacketProcessor;)V";

    @Inject(method = "handleSoundEvent", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER), require = 0)
    private void skyblockSimplified$logSound(ClientboundSoundPacket packet, CallbackInfo ci) {
        if (DianaDevLog.enabled) {
            DianaDevLog.onSound(packet);
        }
    }

    @Inject(method = "handleSoundEntityEvent", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER), require = 0)
    private void skyblockSimplified$logSoundEntity(ClientboundSoundEntityPacket packet, CallbackInfo ci) {
        if (DianaDevLog.enabled) {
            DianaDevLog.onSoundEntity(packet);
        }
    }

    @Inject(method = "handleParticleEvent", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER), require = 0)
    private void skyblockSimplified$logParticle(ClientboundLevelParticlesPacket packet, CallbackInfo ci) {
        if (DianaDevLog.enabled) {
            DianaDevLog.onParticle(packet);
        }
    }

    @Inject(method = "handleAddEntity", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER), require = 0)
    private void skyblockSimplified$logAddEntity(ClientboundAddEntityPacket packet, CallbackInfo ci) {
        if (DianaDevLog.enabled) {
            DianaDevLog.onAddEntity(packet);
        }
    }

    @Inject(method = "handleSetEntityData", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER), require = 0)
    private void skyblockSimplified$logEntityData(ClientboundSetEntityDataPacket packet, CallbackInfo ci) {
        if (DianaDevLog.enabled) {
            DianaDevLog.onEntityData(packet);
        }
    }

    @Inject(method = "handleSetEquipment", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER), require = 0)
    private void skyblockSimplified$logEquipment(ClientboundSetEquipmentPacket packet, CallbackInfo ci) {
        if (DianaDevLog.enabled) {
            DianaDevLog.onEquipment(packet);
        }
    }

    @Inject(method = "handleRemoveEntities", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER), require = 0)
    private void skyblockSimplified$logRemoveEntities(ClientboundRemoveEntitiesPacket packet, CallbackInfo ci) {
        if (DianaDevLog.enabled) {
            DianaDevLog.onRemoveEntities(packet);
        }
    }

    @Inject(method = "handleBlockUpdate", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER), require = 0)
    private void skyblockSimplified$logBlockUpdate(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
        if (DianaDevLog.enabled) {
            DianaDevLog.onBlockUpdate(packet);
        }
    }

    @Inject(method = "handleChunkBlocksUpdate", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER), require = 0)
    private void skyblockSimplified$logSectionBlocks(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
        if (DianaDevLog.enabled) {
            DianaDevLog.onSectionBlocks(packet);
        }
    }

    @Inject(method = "handleSystemChat", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER), require = 0)
    private void skyblockSimplified$logSystemChat(ClientboundSystemChatPacket packet, CallbackInfo ci) {
        if (DianaDevLog.enabled) {
            DianaDevLog.onText(packet.overlay() ? DevLogEvents.ACTIONBAR : DevLogEvents.CHAT,
                    packet.content(), packet.overlay(), "system_chat");
        }
    }

    @Inject(method = "setActionBarText", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER), require = 0)
    private void skyblockSimplified$logActionBar(ClientboundSetActionBarTextPacket packet, CallbackInfo ci) {
        if (DianaDevLog.enabled) {
            DianaDevLog.onText(DevLogEvents.ACTIONBAR, packet.text(), true, "set_action_bar_text");
        }
    }

    @Inject(method = "setTitleText", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER), require = 0)
    private void skyblockSimplified$logTitle(ClientboundSetTitleTextPacket packet, CallbackInfo ci) {
        if (DianaDevLog.enabled) {
            DianaDevLog.onText(DevLogEvents.TITLE, packet.text(), false, "set_title_text");
        }
    }

    @Inject(method = "setSubtitleText", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER), require = 0)
    private void skyblockSimplified$logSubtitle(ClientboundSetSubtitleTextPacket packet, CallbackInfo ci) {
        if (DianaDevLog.enabled) {
            DianaDevLog.onText(DevLogEvents.SUBTITLE, packet.text(), false, "set_subtitle_text");
        }
    }
}
