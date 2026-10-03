/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.helper.visual.model.ExplosionMode;
import sbs.modid.client.helper.visual.model.PotionParticleMode;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Particle filtering at spawn time, around {@code ParticleEngine#createParticle} - the single funnel
 * every particle passes through, including the ones a tracking emitter spawns over time.
 *
 * <ul>
 *   <li><b>Particles module</b>: the player's per-type on/off list
 *       ({@link sbs.modid.client.helper.particles.ParticleFilter}), checked first because an
 *       explicit choice for one type beats the broad-stroke modes below.</li>
 *   <li><b>Explosion</b> (Visuals): Off drops every particle; Half thins them by density
 *       (~20% kept).</li>
 *   <li><b>Potion</b> (Visuals): On cancels them; <b>See-through</b> keeps them but forces their
 *       alpha to 0.2 (80% transparent) via {@link SingleQuadParticleAccessor}; Off leaves them
 *       vanilla.</li>
 * </ul>
 */
@Mixin(ParticleEngine.class)
public class ParticleCleanupMixin {

    /** Fraction of explosion particles to drop for the density-approximated Half mode. */
    private static final double EXPLOSION_THIN_SKIP = 0.8;

    /** See-through opacity forced onto potion particles (0.2 = 80% transparent). */
    private static final float SEETHROUGH_ALPHA = 0.2F;

    @Inject(method = "createParticle", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$filterParticles(ParticleOptions options, double x, double y, double z,
                                                    double vx, double vy, double vz,
                                                    CallbackInfoReturnable<Particle> cir) {
        if (options == null) {
            return;
        }
        ParticleType<?> type = options.getType();

        // Particles module: the player's own per-type list wins over everything below - an explicit
        // "off" for this exact type is a more specific instruction than any of the mode settings.
        if (sbs.modid.client.helper.particles.ParticleFilter.isHidden(type)) {
            cir.setReturnValue(null);
            return;
        }

        SBSConfig.VisualsSettings visuals = ConfigManager.getInstance().get().visuals;

        if (type == ParticleTypes.EXPLOSION || type == ParticleTypes.EXPLOSION_EMITTER) {
            ExplosionMode mode = visuals.explosion;
            if (mode == ExplosionMode.OFF
                    || (mode == ExplosionMode.HALF && ThreadLocalRandom.current().nextDouble() < EXPLOSION_THIN_SKIP)) {
                cir.setReturnValue(null);
            }
            return;
        }

        if (type == ParticleTypes.ENTITY_EFFECT && visuals.potionParticles == PotionParticleMode.ON) {
            cir.setReturnValue(null); // On = hide completely
        }
    }

    /** After creation, force see-through alpha on ambient potion particles (kept, not cancelled). */
    @Inject(method = "createParticle", at = @At("RETURN"))
    private void skyblockSimplified$fadePotionParticles(ParticleOptions options, double x, double y, double z,
                                                        double vx, double vy, double vz,
                                                        CallbackInfoReturnable<Particle> cir) {
        if (options == null || options.getType() != ParticleTypes.ENTITY_EFFECT) {
            return;
        }
        if (ConfigManager.getInstance().get().visuals.potionParticles != PotionParticleMode.SEETHROUGH) {
            return;
        }
        if (cir.getReturnValue() instanceof SingleQuadParticle particle) {
            ((SingleQuadParticleAccessor) particle).skyblockSimplified$setAlpha(SEETHROUGH_ALPHA);
        }
    }
}
