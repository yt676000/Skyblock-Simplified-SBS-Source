/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.multiplayer.ClientChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import sbs.modid.client.helper.terrain.FarTerrainManager;

/**
 * Widens the client chunk cache to the Far Terrain radius.
 *
 * <p>The cache's storage ring is sized by the radius the <b>server</b> announces (a handful of
 * chunks on Hypixel), and {@code replaceWithPacketData} silently discards any chunk outside it -
 * so without this, everything the module serves beyond the server bubble would be thrown away on
 * arrival. Every radius that reaches {@code updateViewRadius} is lifted to at least the module's
 * radius; with the module off the value passes through untouched. The cost of a wide ring is one
 * nullable slot per position - memory only materialises for chunks that actually load.
 *
 * <p>The constructor is widened too ({@code require = 0}: it is belt-and-suspenders, the
 * {@code updateViewRadius} hook plus the manager's per-tick radius check are the functional path),
 * so the cache is already wide during the login chunk burst instead of one radius rebuild later.
 */
@Mixin(ClientChunkCache.class)
public class ChunkCacheRadiusMixin {

    @ModifyVariable(method = "updateViewRadius", at = @At("HEAD"), argsOnly = true)
    private int skyblockSimplified$inflateRadius(int radius) {
        return FarTerrainManager.inflateRadius(radius);
    }

    @ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true, require = 0)
    private static int skyblockSimplified$wideFromBirth(int radius) {
        return FarTerrainManager.inflateRadius(radius);
    }
}
