/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.core.dev.PacketThreadCheck;
import sbs.modid.client.helper.terrain.FarTerrainManager;

/**
 * The Far Terrain module's taps into the chunk stream.
 *
 * <p><b>Capture</b> ({@code handleLevelChunkWithLight} TAIL): every chunk the server delivers on a
 * supported island is remembered. TAIL is only ever reached on the main-thread pass - the
 * network-thread pass throws out of {@code ensureRunningOnSameThread} after scheduling - so the
 * capture runs exactly once per chunk, after vanilla has fully accepted it.
 *
 * <p><b>Keep</b> ({@code handleForgetLevelChunk}, just after {@code ensureRunningOnSameThread}): the
 * server constantly unloads chunks just a few dozen blocks out; vetoing the forget keeps them loaded
 * and rendering. The point after the call is reached only on the client thread, once per packet, and
 * still before vanilla drops the chunk, so the decision reads the player and location safely and the
 * cancel skips {@code drop} and the light removal - deliberate, the chunk keeps rendering. The netty
 * pass never reaches the hook and schedules the packet as usual.
 *
 * <p>The two non-TAIL hooks use the {@link DianaDevLogMixin} injection point; {@code javap} on the
 * 26.2 jar shows one {@code ensureRunningOnSameThread} call at the top of each handler.
 */
@Mixin(ClientPacketListener.class)
public class ChunkStreamMixin {

    private static final String ENSURE = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread("
            + "Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;"
            + "Lnet/minecraft/network/PacketProcessor;)V";

    @Inject(method = "handleLevelChunkWithLight", at = @At("TAIL"))
    private void skyblockSimplified$captureChunk(ClientboundLevelChunkWithLightPacket packet,
                                                 CallbackInfo ci) {
        PacketThreadCheck.check("ChunkStreamMixin.captureChunk");
        FarTerrainManager.getInstance().onChunkFromServer(packet);
    }

    /**
     * A server transfer starts here, and {@code clearClientLevel} a few instructions later frees the
     * whole level. Releasing the widened chunk cache first is what keeps the "Reconfiguring" screen
     * from sitting on a teardown far larger than vanilla ever produces. Injected after the thread
     * redirect, so it runs once, on the client thread, still ahead of {@code clearClientLevel}.
     */
    @Inject(method = "handleConfigurationStart",
            at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER))
    private void skyblockSimplified$releaseBeforeTransfer(ClientboundStartConfigurationPacket packet,
                                                          CallbackInfo ci) {
        PacketThreadCheck.check("ChunkStreamMixin.releaseBeforeTransfer");
        FarTerrainManager.getInstance().onServerTransfer();
    }

    @Inject(method = "handleForgetLevelChunk",
            at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER), cancellable = true)
    private void skyblockSimplified$keepChunk(ClientboundForgetLevelChunkPacket packet,
                                              CallbackInfo ci) {
        PacketThreadCheck.check("ChunkStreamMixin.keepChunk");
        if (FarTerrainManager.getInstance().keepChunk(packet.pos().x(), packet.pos().z())) {
            ci.cancel();
        }
    }
}
