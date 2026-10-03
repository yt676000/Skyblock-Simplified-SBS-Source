/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.dungeons.floorsix.logic.TerracottaTracker;
import sbs.modid.client.dungeons.traps.logic.TrapIndex;

/**
 * Server-driven block changes, observed as they arrive.
 *
 * <p>{@code setServerVerifiedBlockState} is the one funnel both the single-block update packet and
 * the multi-block section update run through, so a HEAD observer here sees every block the server
 * changes - and, because the change has not been applied yet, the level still holds the old state,
 * which is the half of the transition the packet itself does not carry.
 *
 * <p>Feeds the terracotta respawn tracker: a killed terracotta is announced by the server placing a
 * flower pot where it stood, which is invisible to entity watching but unmissable here. The armed
 * check keeps this a single boolean read outside Sadan's fight.
 */
@Mixin(ClientLevel.class)
public abstract class BlockUpdateMixin {

    @Inject(method = "setServerVerifiedBlockState", at = @At("HEAD"))
    private void sbs$blockChanged(BlockPos pos, BlockState state, int flags, CallbackInfo ci) {
        ClientLevel level = (ClientLevel) (Object) this;
        // Commission Route: only cares when the block being routed to stops being the material the
        // commission is about, so this is a position comparison against at most a handful of targets
        // and returns on the first field read while nothing is routed.
        sbs.modid.client.skills.mining.logic.CommissionRoute.getInstance()
                .onBlockChanged(pos, level.getBlockState(pos), state);
        // Trap Highlighter: a dispenser appearing or a wire being removed, as a fact rather than as
        // the result of looking again - which is what lets that feature have no recurring scan at
        // all. Returns on a boolean read outside a dungeon run.
        TrapIndex.getInstance().onBlockChanged(pos, level.getBlockState(pos), state);
        // Treasure chests: the chest block appearing is the position half of the spawn line, and
        // it turning into anything else is the chest being opened or despawning. Returns on a
        // cached boolean outside the Crystal Hollows.
        sbs.modid.client.skills.mining.treasurechest.logic.TreasureChestTracker.getInstance()
                .onBlockChanged(pos, level.getBlockState(pos), state);
        TerracottaTracker tracker = TerracottaTracker.getInstance();
        if (!tracker.armed()) {
            return;
        }
        tracker.onBlockChange(pos, level.getBlockState(pos), state);
    }

    /**
     * A chunk finished streaming in. {@code ClientChunkCache.replaceWithPacketData} calls this after
     * the chunk's data has been read, so the blocks are there to be indexed by the time it fires.
     */
    @Inject(method = "onChunkLoaded", at = @At("TAIL"))
    private void sbs$chunkLoaded(ChunkPos pos, CallbackInfo ci) {
        TrapIndex.getInstance().onChunkLoaded(pos);
    }

    /**
     * A chunk left. Everything indexed inside it goes with it - a trap in an unloaded chunk cannot be
     * verified, and drawing one from memory is a box over a room the client can no longer see.
     */
    @Inject(method = "unload", at = @At("HEAD"))
    private void sbs$chunkUnloaded(LevelChunk chunk, CallbackInfo ci) {
        TrapIndex.getInstance().onChunkUnloaded(chunk);
    }
}
