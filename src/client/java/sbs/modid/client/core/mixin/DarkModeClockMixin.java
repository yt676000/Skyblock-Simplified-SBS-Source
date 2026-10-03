/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.ClientClockManager;
import net.minecraft.core.Holder;
import net.minecraft.world.clock.WorldClock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sbs.modid.client.helper.visual.logic.DarkMode;

/**
 * Dark Mode's client-side time: the world clock reports the hour the player picked.
 *
 * <p>{@code ClientClockManager.getTotalTicks} is the one place the client answers "what time is it".
 * In 26.2 the sky is no longer drawn from a time value read on the spot - every time-driven property
 * is an {@code EnvironmentAttribute} sampled by {@code AttributeTrackSampler}, and that sampler's
 * only input is this method: the sun and moon angles, the star brightness, the sky and fog colours,
 * the sky light factor and the ambient light colour all come out of it. Overriding here is therefore
 * a real change of the time of day, with the sun standing where the setting says, rather than a
 * tinted sky drawn over the old one.
 *
 * <p>Nothing is sent to the server and nothing is stored in the level: the override lives entirely in
 * the return value of this call, so other players keep their own time and turning the setting off
 * restores the server's time on the very next tick.
 *
 * <p>Free while off - one config read and a branch - and nearly free while on, because the sampler
 * caches its result per game tick and a constant time is sampled once a tick, not once a frame.
 */
@Mixin(ClientClockManager.class)
public abstract class DarkModeClockMixin {

    @Inject(method = "getTotalTicks", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$clientTime(Holder<WorldClock> clock,
                                               CallbackInfoReturnable<Long> cir) {
        long override = DarkMode.clockOverride();
        if (override >= 0L) {
            cir.setReturnValue(override);
        }
    }
}
