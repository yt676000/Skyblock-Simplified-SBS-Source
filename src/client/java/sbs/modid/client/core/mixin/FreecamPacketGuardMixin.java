/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.helper.build.logic.FreecamPacketGuard;
import sbs.modid.client.helper.build.logic.FreecamPacketLog;

/**
 * Freecam's packet guard, on the one method every outgoing packet passes: {@code send(Packet,
 * ChannelFutureListener, boolean)} - the one- and two-argument overloads delegate to it (26.2
 * bytecode). While freecam is on, {@link FreecamPacketGuard#filter} drops what a player standing
 * still would not send and swaps movement packets for ones rebuilt from the player entity; otherwise
 * the packet goes through untouched. The developer-mode counter sees what is actually sent.
 */
@Mixin(Connection.class)
public abstract class FreecamPacketGuardMixin {

    /** Set while the rebuilt packet is being sent, so it is not filtered a second time. */
    private static final ThreadLocal<Boolean> SBS$RESENDING = ThreadLocal.withInitial(() -> false);

    @Shadow
    public abstract void send(Packet<?> packet, ChannelFutureListener listener, boolean flush);

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V",
            at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$freecamGuard(Packet<?> packet, ChannelFutureListener listener, boolean flush,
                                                 CallbackInfo ci) {
        if (SBS$RESENDING.get()) {
            return;
        }
        Packet<?> out = FreecamPacketGuard.filter((Connection) (Object) this, packet);
        if (out == null) {
            ci.cancel();
            return;
        }
        FreecamPacketLog.record(out);
        if (out != packet) {
            ci.cancel();
            SBS$RESENDING.set(true);
            try {
                send(out, listener, flush);
            } finally {
                SBS$RESENDING.set(false);
            }
        }
    }
}
