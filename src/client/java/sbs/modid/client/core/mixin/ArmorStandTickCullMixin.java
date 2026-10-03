/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.helper.performance.logic.ArmorStandCulling;

/**
 * The Performance module's <b>tick cull</b>: armor stands that are not rendered anyway skip their
 * client tick entirely.
 *
 * <p>An armor stand runs the full {@code LivingEntity} tick - equipment-change scans, limb and
 * head-turn bookkeeping, movement interpolation - every tick, for every stand in tracking range.
 * Cancelling {@code tickNonPassenger} at the head skips all of it (plus the old-position
 * bookkeeping and the passenger chain, which is why {@link ArmorStandCulling#skipTick} refuses
 * stands that carry a rider). A culled stand simply freezes in place: its position is only ever
 * moved by tick-side interpolation toward the server's packets, so when ticking resumes it glides
 * to the current target within a few ticks - and the filter keeps a margin beyond the render
 * distance, so that glide has already happened by the time the stand is visible again.
 */
@Mixin(ClientLevel.class)
public abstract class ArmorStandTickCullMixin {

    @Inject(method = "tickNonPassenger(Lnet/minecraft/world/entity/Entity;)V",
            at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$cullArmorStandTicks(Entity entity, CallbackInfo ci) {
        if (ArmorStandCulling.skipTick(entity)) {
            ci.cancel();
        }
    }
}
