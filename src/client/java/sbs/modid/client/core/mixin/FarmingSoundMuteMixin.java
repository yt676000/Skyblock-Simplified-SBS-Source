/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sbs.modid.client.skills.farming.logic.FarmingSounds;

/**
 * Drops the farming sounds the Farming / Garden modules have been asked to silence (the hoe
 * level-up jingle, the pest vacuum loop).
 *
 * <p>Injected at the head of {@code SoundEngine#play} and returning {@code NOT_STARTED}, which is
 * the same answer the engine gives for a sound it decided not to start – so nothing downstream can
 * tell the difference between a muted sound and one the engine skipped on its own. Cancelling here
 * rather than setting the volume to zero also means the sound never occupies a channel.
 *
 * <p>The decision itself lives in {@link FarmingSounds}; with every toggle off it returns
 * {@code false} on the first branch, so a player who wants none of this pays one string compare per
 * sound.
 */
@Mixin(SoundEngine.class)
public class FarmingSoundMuteMixin {

    @Inject(method = "play", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$muteFarmingSounds(SoundInstance instance,
                                                      CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
        if (instance == null) {
            return;
        }
        var id = instance.getIdentifier();
        if (id != null && FarmingSounds.muted(id.toString())) {
            cir.setReturnValue(SoundEngine.PlayResult.NOT_STARTED);
        }
    }
}
