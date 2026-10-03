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
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.helper.terrain.FarTerrainManager;

/**
 * The Far Terrain module's two taps into the chunk stream.
 *
 * <p><b>Capture</b> ({@code handleLevelChunkWithLight} TAIL): every chunk the server delivers on a
 * supported island is remembered. TAIL is only ever reached on the main-thread pass - the
 * network-thread pass throws out of {@code ensureRunningOnSameThread} after scheduling - so the
 * capture runs exactly once per chunk, after vanilla has fully accepted it.
 *
 * <p><b>Keep</b> ({@code handleForgetLevelChunk} HEAD): the server constantly unloads chunks just
 * a few dozen blocks out; vetoing the forget keeps them loaded and rendering. The manager answers
 * {@code false} on the network-thread pass (letting vanilla schedule the packet as usual) and
 * makes the actual decision on the main-thread pass, where reading the player and location is
 * safe. Cancelling skips the light removal too - deliberate, the chunk keeps rendering.
 */
@Mixin(ClientPacketListener.class)
public class ChunkStreamMixin {

    @Inject(method = "handleLevelChunkWithLight", at = @At("TAIL"))
    private void skyblockSimplified$captureChunk(ClientboundLevelChunkWithLightPacket packet,
                                                 CallbackInfo ci) {
        FarTerrainManager.getInstance().onChunkFromServer(packet);
    }

    /**
     * A server transfer starts here, and {@code clearClientLevel} a few instructions later frees the
     * whole level. Releasing the widened chunk cache first is what keeps the "Reconfiguring" screen
     * from sitting on a teardown far larger than vanilla ever produces.
     */
    @Inject(method = "handleConfigurationStart", at = @At("HEAD"))
    private void skyblockSimplified$releaseBeforeTransfer(
            net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket packet,
            CallbackInfo ci) {
        FarTerrainManager.getInstance().onServerTransfer();
    }

    @Inject(method = "handleForgetLevelChunk", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$keepChunk(ClientboundForgetLevelChunkPacket packet,
                                              CallbackInfo ci) {
        if (FarTerrainManager.getInstance().keepChunk(packet.pos().x(), packet.pos().z())) {
            ci.cancel();
        }
    }
}
