/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.helper.timers.ServerWorldTime;
import sbs.modid.client.ui.hud.logic.ServerStatsTracker;

/**
 * The time-update packet, read twice.
 *
 * <p>Its <b>arrival cadence</b> feeds the {@link ServerStatsTracker} TPS estimator: the server sends
 * one every 20 ticks, so the interval between them IS the server tick rate. Injecting at TAIL means
 * we only run on the main-thread invocation (the netty thread re-dispatches before the body
 * executes), keeping the measurement free of thread jitter.
 *
 * <p>Its <b>contents</b> feed {@link ServerWorldTime}, which is the only place the client sees the
 * server's clocks before the client's own machinery gets to reinterpret them - see that class for
 * the two ways the level's accessors misreport them.
 */
@Mixin(ClientPacketListener.class)
public class TimeUpdateMixin {

    @Inject(method = "handleSetTime", at = @At("TAIL"))
    private void skyblockSimplified$trackTimeUpdate(ClientboundSetTimePacket packet, CallbackInfo ci) {
        ServerStatsTracker.getInstance().onTimeUpdate();
        ServerWorldTime.onTimeUpdate(packet);
    }
}
