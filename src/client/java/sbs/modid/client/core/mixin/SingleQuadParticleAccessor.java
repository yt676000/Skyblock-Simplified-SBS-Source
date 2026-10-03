/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.particle.SingleQuadParticle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the protected {@code alpha} field of {@link SingleQuadParticle} so the Visuals module can
 * force ambient potion particles to a see-through opacity without cancelling them.
 */
@Mixin(SingleQuadParticle.class)
public interface SingleQuadParticleAccessor {

    @Accessor("alpha")
    void skyblockSimplified$setAlpha(float alpha);
}
