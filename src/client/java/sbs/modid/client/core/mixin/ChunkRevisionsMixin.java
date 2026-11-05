/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.core.world.ChunkRevisions;

/**
 * Feeds {@link ChunkRevisions}. Every hook is at TAIL: the network-thread pass throws out of
 * {@code ensureRunningOnSameThread} before reaching it, so each runs once, on the main thread, after
 * vanilla applied the packet. A forget vetoed by {@code ChunkStreamMixin} never reaches TAIL - the
 * chunk stays loaded and keeps its revision.
 */
@Mixin(ClientPacketListener.class)
public class ChunkRevisionsMixin {

    @Inject(method = "handleBlockUpdate", at = @At("TAIL"))
    private void skyblockSimplified$blockRevision(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
        BlockPos pos = packet.getPos();
        ChunkRevisions.bump(pos.getX() >> 4, pos.getZ() >> 4);
    }

    @Inject(method = "handleChunkBlocksUpdate", at = @At("TAIL"))
    private void skyblockSimplified$sectionRevision(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
        SectionPos section = ((SectionBlocksUpdateAccessor) packet).skyblockSimplified$sectionPos();
        ChunkRevisions.bump(section.x(), section.z());
    }

    @Inject(method = "handleLevelChunkWithLight", at = @At("TAIL"))
    private void skyblockSimplified$chunkRevision(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        ChunkRevisions.bump(packet.getX(), packet.getZ());
    }

    @Inject(method = "handleForgetLevelChunk", at = @At("TAIL"))
    private void skyblockSimplified$forgetRevision(ClientboundForgetLevelChunkPacket packet, CallbackInfo ci) {
        ChunkRevisions.forget(packet.pos().x(), packet.pos().z());
    }
}
