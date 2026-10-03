/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sbs.modid.client.helper.performance.logic.ArmorStandCulling;

/**
 * The Performance module's <b>render cull</b>: armor stands the filter rejects never enter the
 * frame at all.
 *
 * <p>{@code shouldRender} is the earliest per-entity decision the renderer makes - a {@code false}
 * here skips render-state extraction, pose math, the layer stack, shadow and nametag alike, which
 * is the whole point: the win is not drawing fewer pixels (an invisible stand draws none anyway)
 * but skipping the per-frame CPU work vanilla spends preparing entities that end up contributing
 * nothing. The dispatcher is the one choke point every entity type passes through.
 *
 * <p>It is also where <b>Hide Nearby Players</b> skips the other players it hides - a second,
 * separate check in the same injection rather than a second mixin on the same method. Its answer is
 * a set lookup against what {@code PlayerHiding.tick} decided; nothing is worked out per frame.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class ArmorStandRenderCullMixin {

    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private <E extends Entity> void skyblockSimplified$cullArmorStands(E entity, Frustum frustum,
            double camX, double camY, double camZ, CallbackInfoReturnable<Boolean> cir) {
        if (ArmorStandCulling.skipRender(entity, camX, camY, camZ)
                || sbs.modid.client.helper.hideplayers.logic.PlayerHiding.skipRender(entity)
                // Freecam on a server: nothing but blocks from the camera's view, so it cannot scout.
                || sbs.modid.client.helper.build.logic.Freecam.hidesEntity(entity)) {
            cir.setReturnValue(false);
        }
    }
}
