/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sbs.modid.client.core.player.BlockBreakEvents;

/**
 * The one "local player broke a block" hook: HEAD of {@code MultiPlayerGameMode.destroyBlock(BlockPos)},
 * where both the instant-break and the finished-mining paths end, reading the block state while it
 * is still present and handing it to {@link BlockBreakEvents}. Features register there (Secret
 * Routes' breaker scan, the mimic watcher, Farming Speed) instead of adding another hook.
 *
 * <p>Purely observational: never cancels and returns nothing.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class BlockBreakTrackMixin {

    @Inject(method = "destroyBlock", at = @At("HEAD"))
    private void skyblockSimplified$onDestroyBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (pos == null) {
            return;
        }
        Level level = Minecraft.getInstance().level;
        if (level != null) {
            BlockBreakEvents.fire(pos, level.getBlockState(pos));
        }
    }
}
