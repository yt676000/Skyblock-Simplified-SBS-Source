/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sbs.modid.client.helper.build.logic.Freecam;

/**
 * No world interaction from freecam - the second lock, on the interaction controller every break,
 * use, attack and entity interaction goes through before a packet is built. While freecam is on each
 * of them returns "nothing happened" at once, so no such packet can leave the client whatever path
 * called it. The first lock ({@code FreecamClickMixin}) stops the clicks before they get here.
 *
 * <p>Build Tools' own singleplayer edits do not come through here - they run on the integrated
 * server's thread - so they keep working with freecam on, as asked.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class FreecamInteractionGuardMixin {

    @Inject(method = "startDestroyBlock", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$noBreak(BlockPos pos, Direction face, CallbackInfoReturnable<Boolean> cir) {
        if (Freecam.active()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "continueDestroyBlock", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$noBreakHold(BlockPos pos, Direction face, CallbackInfoReturnable<Boolean> cir) {
        if (Freecam.active()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$noUseOn(LocalPlayer player, InteractionHand hand, BlockHitResult hit,
                                            CallbackInfoReturnable<InteractionResult> cir) {
        if (Freecam.active()) {
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }

    @Inject(method = "useItem", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$noUse(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        if (Freecam.active()) {
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }

    @Inject(method = "attack", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$noAttack(Player player, Entity target, CallbackInfo ci) {
        if (Freecam.active()) {
            ci.cancel();
        }
    }

    @Inject(method = "interact", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$noInteract(Player player, Entity target, EntityHitResult hit, InteractionHand hand,
                                               CallbackInfoReturnable<InteractionResult> cir) {
        if (Freecam.active()) {
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }
}
