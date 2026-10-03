/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.FallingBlockRenderer;
import net.minecraft.world.entity.item.FallingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sbs.modid.client.core.config.ConfigManager;

/**
 * Visuals module: <b>Hide Falling Blocks</b>. Reports every falling-block entity as "not visible", so
 * the renderer never extracts a state for it – the cheapest possible way to drop them, and it also
 * takes their shadow with it. Purely visual: the blocks still fall and still land.
 */
@Mixin(FallingBlockRenderer.class)
public class FallingBlockHideMixin {

    @Inject(method = "shouldRender(Lnet/minecraft/world/entity/item/FallingBlockEntity;Lnet/minecraft/client/renderer/culling/Frustum;DDD)Z",
            at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$hideFallingBlocks(FallingBlockEntity entity, Frustum frustum,
                                                      double cameraX, double cameraY, double cameraZ,
                                                      CallbackInfoReturnable<Boolean> cir) {
        if (ConfigManager.getInstance().get().visuals.hideFallingBlocks) {
            cir.setReturnValue(false);
        }
    }
}
