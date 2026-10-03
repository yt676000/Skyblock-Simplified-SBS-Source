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
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sbs.modid.client.helper.build.logic.MagicStickInput;

/**
 * The Magic Stick Thingy's clicks: left-click on a block sets selection corner 1 instead of breaking
 * it, right-click sets corner 2 instead of using it.
 *
 * <p>Cancels only when {@link MagicStickInput} claims the click, which it does only in singleplayer
 * with the stick in the main hand. Everywhere else - every server, every other item - these hooks
 * return without touching anything, and the click goes through as vanilla.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class BuildWandMixin {

    @Inject(method = "startDestroyBlock", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$wandCorner1(BlockPos pos, Direction face, CallbackInfoReturnable<Boolean> cir) {
        if (MagicStickInput.onStartDestroy(pos)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "continueDestroyBlock", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$wandHold(BlockPos pos, Direction face, CallbackInfoReturnable<Boolean> cir) {
        if (MagicStickInput.onContinueDestroy()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$wandCorner2(LocalPlayer player, InteractionHand hand, BlockHitResult hit,
                                                CallbackInfoReturnable<InteractionResult> cir) {
        if (hit != null && MagicStickInput.onUseOn(hand, hit.getBlockPos())) {
            cir.setReturnValue(InteractionResult.SUCCESS);
        }
    }

    /** Right-click in the air with the stick: Sneak clears the selection; swallowed either way. */
    @Inject(method = "useItem", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$wandClear(net.minecraft.world.entity.player.Player player, InteractionHand hand,
                                              CallbackInfoReturnable<InteractionResult> cir) {
        if (MagicStickInput.onUseAir(hand)) {
            cir.setReturnValue(InteractionResult.SUCCESS);
        }
    }
}
