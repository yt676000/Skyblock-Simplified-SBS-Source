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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sbs.modid.client.dungeons.floorseven.logic.LightsDeviceTracker;

/**
 * Optional guard for the F7 Lights device (off by default): a right-click of the player's own on a
 * device lever that is not in the current answer is dropped before it is sent.
 *
 * <p>It only ever withholds the player's own click - it never sends one - and it fails open: with
 * the toggle off, outside the Goldor phase, or without the full wiring known, every click passes.
 * Withholding a click wins nothing over another player; it is the same class of protection as the
 * item drop guards.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class LightsLeverGuardMixin {

    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$lightsGuard(LocalPlayer player, InteractionHand hand, BlockHitResult hit,
                                                CallbackInfoReturnable<InteractionResult> cir) {
        LightsDeviceTracker tracker = LightsDeviceTracker.getInstance();
        if (tracker.armed() && tracker.guardBlocks(hit.getBlockPos())) {
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }
}
