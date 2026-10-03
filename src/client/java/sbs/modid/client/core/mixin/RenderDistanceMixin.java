/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sbs.modid.client.helper.terrain.FarTerrainManager;

/**
 * Lifts the two ceilings that keep Far Terrain invisible.
 *
 * <p><b>The server clamp</b> is the one that actually bites: vanilla's
 * {@code getEffectiveRenderDistance()} returns {@code min(yourSlider, serverRenderDistance)}, and
 * Hypixel announces a small radius - so no matter how far the module remembers, the renderer was
 * told to draw a handful of chunks and drew a handful of chunks. Remembered terrain is served by
 * this client, not the server, so the server's number has no authority over it; while the module is
 * on, the player's own slider wins.
 *
 * <p><b>The slider ceiling</b> is raised separately, in
 * {@link sbs.modid.client.helper.terrain.FarTerrainRenderDistance} - this class only stops the
 * server from overriding whatever it is set to.
 *
 * <p>With the module off, both behave exactly as vanilla.
 */
@Mixin(Options.class)
public class RenderDistanceMixin {

    @Inject(method = "getEffectiveRenderDistance", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$ignoreServerClamp(CallbackInfoReturnable<Integer> cir) {
        Integer own = FarTerrainManager.ownRenderDistance();
        if (own != null) {
            cir.setReturnValue(own);
        }
    }
}
