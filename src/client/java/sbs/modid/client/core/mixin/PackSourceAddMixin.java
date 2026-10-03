/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.repository.RepositorySource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import sbs.modid.client.helper.texture.logic.HypixelPackFallback;

import java.util.Arrays;

/**
 * Registers the {@link HypixelPackFallback.Source} on the client's {@code PackRepository} at
 * construction, so the cached Hypixel pack participates in every pack (re)load. The source itself
 * decides per reload whether to contribute (toggle on + zip cached) – injecting it unconditionally
 * keeps this mixin trivial.
 */
@Mixin(Minecraft.class)
public abstract class PackSourceAddMixin {

    @ModifyArg(method = "<init>",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/packs/repository/PackRepository;<init>([Lnet/minecraft/server/packs/repository/RepositorySource;)V"),
            index = 0)
    private RepositorySource[] skyblockSimplified$addFallbackSource(RepositorySource[] sources) {
        RepositorySource[] extended = Arrays.copyOf(sources, sources.length + 1);
        extended[sources.length] = new HypixelPackFallback.Source();
        return extended;
    }
}
