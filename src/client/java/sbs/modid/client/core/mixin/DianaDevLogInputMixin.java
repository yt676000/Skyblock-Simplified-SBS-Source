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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sbs.modid.client.combat.diana.devlog.DianaDevLog;

/**
 * The player's own clicks, for Diana Log Mode: a right click ({@code useItem}), a right click on a
 * block ({@code useItemOn}) and a left click on a block ({@code startDestroyBlock}, which is how a
 * burrow is dug).
 *
 * <p>The same three moments Fabric's use-item, use-block and attack-block callbacks fire from, read
 * with a mixin because this mod hooks vanilla directly rather than through the Fabric event bus. A
 * right click aimed at a block can reach both {@code useItemOn} and then {@code useItem} - vanilla
 * calls the second when the first passes - so both are logged as what they are rather than merged.
 *
 * <p><b>Observation only.</b> HEAD, never cancellable, nothing returned: the interaction proceeds
 * exactly as it would without this. While the log is off each injection costs one static volatile read.
 * {@code require = 0} for the reason given on {@link DianaDevLogMixin}: a diagnostic must never be
 * the thing that crashes a world join.
 */
@Mixin(MultiPlayerGameMode.class)
public class DianaDevLogInputMixin {

    @Inject(method = "useItem", at = @At("HEAD"), require = 0)
    private void skyblockSimplified$logUseItem(Player player, InteractionHand hand,
                                               CallbackInfoReturnable<?> cir) {
        if (DianaDevLog.enabled && player != null) {
            DianaDevLog.onUseItem(player, hand);
        }
    }

    @Inject(method = "useItemOn", at = @At("HEAD"), require = 0)
    private void skyblockSimplified$logUseItemOn(LocalPlayer player, InteractionHand hand, BlockHitResult hit,
                                                 CallbackInfoReturnable<?> cir) {
        if (DianaDevLog.enabled && player != null && hit != null) {
            DianaDevLog.onUseItemOn(player, hand, hit);
        }
    }

    @Inject(method = "startDestroyBlock", at = @At("HEAD"), require = 0)
    private void skyblockSimplified$logAttackBlock(BlockPos pos, Direction direction,
                                                   CallbackInfoReturnable<Boolean> cir) {
        if (DianaDevLog.enabled && pos != null) {
            DianaDevLog.onAttackBlock(pos, direction);
        }
    }
}
