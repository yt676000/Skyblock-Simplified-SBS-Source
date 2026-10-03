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
import sbs.modid.client.core.dev.SoundProbe;
import sbs.modid.client.core.sound.SoundListeners;

/**
 * Every sound the game is about to play, handed to the sound listeners and to {@link SoundProbe}.
 *
 * <p>{@code SoundEngine.play(SoundInstance)} is the single funnel for all of it - server-sent sounds,
 * {@code Entity.playSound}, music and UI clicks alike - which {@link SoundControlMixin} already
 * establishes and relies on. This sits at the same point for the read-only half, and stays a separate
 * mixin because muting a sound and listening to one are different jobs: the Sound Manager's hook
 * returns early whenever its module is off, and a listener that lived inside it would go deaf with it.
 *
 * <p><b>Not cancellable, and it must stay that way.</b> Nothing on this path may change whether a
 * sound plays; that decision belongs to {@link SoundControlMixin} alone, where the player's settings
 * are. A listener that could cancel would be a second, invisible mute nobody configured.
 *
 * <p>Costs one static boolean read per sound when nothing is listening - the same shape as
 * {@link ParticleProbeMixin}, and for the same reason: this path is hot.
 */
@Mixin(SoundEngine.class)
public class SoundProbeMixin {

    @Inject(method = "play", at = @At("HEAD"))
    private void skyblockSimplified$observeSound(SoundInstance instance,
                                                 CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
        if (SoundProbe.ARMED) {
            SoundProbe.getInstance().onSound(instance);
        }
        if (SoundListeners.ACTIVE) {
            SoundListeners.dispatch(instance);
        }
    }
}
