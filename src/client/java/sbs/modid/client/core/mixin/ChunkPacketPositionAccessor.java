/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Lets a stored chunk packet be relocated before it is handed to the chunk intake.
 *
 * <p>This is the whole mechanism behind the neighbour-island view: everything <i>inside</i> a chunk
 * packet is chunk-local (block entities carry packed section-relative positions, light data is
 * per-section), so the packet's {@code x}/{@code z} are the single authority on where the chunk
 * lands. Rewriting just those two fields on a decoded packet re-homes the entire chunk, blocks,
 * light and all - no payload surgery. They are {@code private final}, hence {@link Mutable}.
 */
@Mixin(ClientboundLevelChunkWithLightPacket.class)
public interface ChunkPacketPositionAccessor {

    @Accessor("x")
    @Mutable
    void skyblockSimplified$setX(int x);

    @Accessor("z")
    @Mutable
    void skyblockSimplified$setZ(int z);
}
