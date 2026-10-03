/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.SkyblockSimplifiedSBS;

/**
 * TEMPORARY DIAGNOSTIC - remove once the SkyBlock join hang is found.
 *
 * <p>Joining SkyBlock stalls in the configuration phase: the lobby level is torn down, the
 * "Reconfiguring" screen appears, and then nothing happens until Hypixel kicks with "Configuration
 * took too long!". No exception is logged and the client keeps ticking, so the ordinary logs say
 * nothing at all about where the handshake stops.
 *
 * <p>This logs every packet in both directions while there is no client level - which is exactly the
 * login and (re)configuration window and nowhere else, so it is silent in the lobby and silent once
 * SkyBlock is loaded. The last few lines before the silence name the packet the client failed to
 * answer.
 */
@Mixin(Connection.class)
public class ConfigPhaseTraceMixin {

    /** Hard ceiling, so a mistake here can never turn into an unbounded log. */
    private static final int SBS_TRACE_LIMIT = 4000;

    private static int sbsTraceCount;

    private static void sbsTrace(String direction, Packet<?> packet) {
        if (Minecraft.getInstance().level != null || sbsTraceCount >= SBS_TRACE_LIMIT) {
            return;
        }
        sbsTraceCount++;
        String name = packet.getClass().getSimpleName();
        String extra = "";
        if (packet instanceof ClientboundCustomPayloadPacket custom) {
            extra = " payload=" + custom.payload().type().id();
        } else if (packet instanceof ServerboundCustomPayloadPacket custom) {
            extra = " payload=" + custom.payload().type().id();
        }
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][NetTrace] {} {}{}", direction, name, extra);
    }

    @Inject(method = "channelRead0", at = @At("HEAD"))
    private void skyblockSimplified$traceInbound(ChannelHandlerContext ctx, Packet<?> packet,
                                                 CallbackInfo ci) {
        sbsTrace("<--", packet);
    }

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V",
            at = @At("HEAD"))
    private void skyblockSimplified$traceOutbound(Packet<?> packet, ChannelFutureListener listener,
                                                  boolean flush, CallbackInfo ci) {
        sbsTrace("-->", packet);
    }
}
