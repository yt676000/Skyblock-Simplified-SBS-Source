/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.dungeons.run.render.DungeonHighlight;

/**
 * Starred mobs, "Outline" style: gives a visible starred (✯) dungeon mob vanilla's glowing
 * silhouette in the configured colour.
 *
 * <p>{@code extractRenderState} sets {@code outlineColor} from the glowing flag (0 = no outline) and
 * nothing later overwrites it; any non-zero value puts the entity into the outline pass, which runs
 * whenever a player exists. Injected at the tail, so this wins over the team colour.
 *
 * <p>The vanilla outline ignores depth. That is acceptable only because the id set holds just the
 * mobs that passed the tracker's line-of-sight test, re-checked every few ticks: a mob that walks
 * behind a wall loses its outline at the next re-check.
 */
@Mixin(EntityRenderer.class)
public abstract class StarredMobOutlineMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;F)V",
            at = @At("TAIL"))
    private void skyblockSimplified$starredOutline(Entity entity, EntityRenderState state,
                                                   float partialTick, CallbackInfo ci) {
        int color = DungeonHighlight.getInstance().starredOutlineColor(entity.getId());
        if (color != 0) {
            state.outlineColor = color;
        }
    }
}
