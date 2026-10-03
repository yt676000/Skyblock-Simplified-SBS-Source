/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sbs.modid.client.helper.build.logic.Freecam;

/**
 * No world interaction from freecam - the first of two locks. The clicks the game turns into an
 * attack, a block break, a use or a pick never start: a left or right click goes to the Build Tools
 * selection alone ({@link Freecam#click}) and nothing else. The drop, swap-hands and hotbar keys are
 * drained too, so no item leaves the hand or changes while the camera is away.
 *
 * <p>The second lock is {@code FreecamInteractionGuardMixin} on the interaction controller itself, for
 * any path that reaches it some other way. Together they are why the player cannot act through a wall
 * the camera looks through.
 */
@Mixin(Minecraft.class)
public abstract class FreecamClickMixin {

    @Shadow
    public Options options;

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$freecamAttack(CallbackInfoReturnable<Boolean> cir) {
        if (Freecam.active()) {
            Freecam.click(true);
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$freecamUse(CallbackInfo ci) {
        if (Freecam.active()) {
            Freecam.click(false);
            ci.cancel();
        }
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$freecamHold(boolean leftClick, CallbackInfo ci) {
        if (Freecam.active()) {
            ci.cancel();
        }
    }

    @Inject(method = "pickBlockOrEntity", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$freecamPick(CallbackInfo ci) {
        if (Freecam.active()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleKeybinds", at = @At("HEAD"))
    private void skyblockSimplified$freecamDrainKeys(CallbackInfo ci) {
        if (!Freecam.active() || options == null) {
            return;
        }
        drain(options.keyDrop);
        drain(options.keySwapOffhand);
        for (KeyMapping slot : options.keyHotbarSlots) {
            drain(slot);
        }
    }

    private static void drain(KeyMapping key) {
        while (key.consumeClick()) {
            // swallowed: this press does nothing while the camera is away
        }
    }
}
