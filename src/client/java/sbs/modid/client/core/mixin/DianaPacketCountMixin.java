/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.combat.diana.devlog.DevLogPackets;
import sbs.modid.client.combat.diana.devlog.DianaDevLog;

/**
 * Every inbound packet, counted by class while Diana Log Mode is on - so the capture shows packet
 * types nobody thought to hook.
 *
 * <p>This runs on the <b>netty thread</b>, before the packet is handed to the client thread. It
 * increments a counter and nothing else: no logging, no allocation after a class's first packet, no
 * reading of the packet beyond its class and, for a bundle, its contents' classes. The client tick
 * reads and resets the counters once a second ({@code DianaDevLog}). Nothing is cancelled.
 * {@code require = 0} for the reason given on {@link DianaDevLogMixin}.
 */
@Mixin(Connection.class)
public class DianaPacketCountMixin {

    @Inject(method = "channelRead0", at = @At("HEAD"), require = 0)
    private void skyblockSimplified$countPacket(ChannelHandlerContext ctx, Packet<?> packet, CallbackInfo ci) {
        if (DianaDevLog.enabled) {
            DevLogPackets.count(packet);
        }
    }
}
