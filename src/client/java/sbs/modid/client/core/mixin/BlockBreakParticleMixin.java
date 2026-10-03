/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.helper.particles.ParticleFilter;

/**
 * The block dust the Particles module's "block" choice could not reach.
 *
 * <p>{@code addDestroyBlockEffect} (a block broken - yours, and other players' through the level
 * event) and {@code addBreakingBlockEffect} (the crack dust while mining) build their
 * {@code TerrainParticle}s themselves and hand them straight to {@code ParticleEngine.add}, so they
 * never pass {@code createParticle}, where {@link ParticleCleanupMixin} filters. Verified with javap
 * on the 26.2 client jar: both methods only spawn particles - the break sound, the crack overlay and
 * the server interaction live elsewhere and are untouched by cancelling them here.
 *
 * <p>One switch for both, the existing {@code block} id: the Particles screen lists registry ids,
 * and splitting break from crack would need a second id scheme for something the registry calls one
 * particle.
 */
@Mixin(ClientLevel.class)
public abstract class BlockBreakParticleMixin {

    @Inject(method = "addDestroyBlockEffect", at = @At("HEAD"), cancellable = true)
    private void sbs$hideBreakDust(BlockPos pos, BlockState state, CallbackInfo ci) {
        if (ParticleFilter.blockDustHidden()) {
            ci.cancel();
        }
    }

    @Inject(method = "addBreakingBlockEffect", at = @At("HEAD"), cancellable = true)
    private void sbs$hideCrackDust(BlockPos pos, Direction side, CallbackInfo ci) {
        if (ParticleFilter.blockDustHidden()) {
            ci.cancel();
        }
    }
}
