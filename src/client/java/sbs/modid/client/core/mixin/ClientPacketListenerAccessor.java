/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the server-announced chunk view radius. The Far Terrain module has to pass a real value
 * into {@code ClientChunkCache.updateViewRadius} when it widens or narrows the cache, and this
 * field is the only place the client keeps what the server actually asked for - guessing lower
 * would make the rebuild drop live server chunks that are never re-sent.
 */
@Mixin(ClientPacketListener.class)
public interface ClientPacketListenerAccessor {

    @Accessor("serverChunkRadius")
    int skyblockSimplified$serverChunkRadius();
}
