/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sbs.modid.client.core.sound.SoundControl;

/**
 * The Sound Manager module's hook: mutes and rescales sounds before the engine plays them.
 *
 * <p>{@code SoundEngine.play(SoundInstance)} is the single funnel for every sound in the game -
 * {@code SoundManager.play} is a two-line delegation to it, and everything else (server-sent
 * sounds, {@code Entity.playSound}, music, UI clicks) arrives through that. One hook therefore
 * covers all of it. What it cannot cover is the <b>narrator</b>: that is OS text-to-speech and never
 * touches the sound engine - which is exactly why the narrator alert channel still works when a
 * player mutes everything here.
 *
 * <p>Muting cancels with {@link SoundEngine.PlayResult#NOT_STARTED}, the engine's own value for
 * "this did not play", so callers that check the result are told the truth rather than being handed
 * a fake success.
 *
 * <p>The volume rescale is a {@link WrapOperation} rather than a {@code @Redirect}: redirects claim
 * a call site exclusively, and the sound path is a plausible place for another mod to want the same
 * one. Wrapping composes instead of conflicting - the other mod's handler still runs, and this one
 * scales whatever it returned.
 */
@Mixin(SoundEngine.class)
public abstract class SoundControlMixin {

    /** Drops a muted sound before the engine does any work for it. */
    @Inject(method = "play", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$muteSound(SoundInstance instance,
                                              CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
        if (!SoundControl.enabled() || instance == null) {
            return;
        }
        if (!SoundControl.allowed(instance.getIdentifier())) {
            cir.setReturnValue(SoundEngine.PlayResult.NOT_STARTED);
        }
    }

    /** Applies the player's per-sound volume to the value the engine is about to use. */
    @WrapOperation(method = "play",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/resources/sounds/SoundInstance;getVolume()F"))
    private float skyblockSimplified$scaleVolume(SoundInstance instance,
                                                 Operation<Float> original) {
        float volume = original.call(instance);
        if (!SoundControl.enabled() || instance == null) {
            return volume;
        }
        return volume * SoundControl.volumeFactor(instance.getIdentifier());
    }
}
