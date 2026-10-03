/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.player;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import sbs.modid.SkyblockSimplifiedSBS;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * "The local player broke a block": one listener list, fed by the single break hook
 * ({@code BlockBreakTrackMixin}, HEAD of {@code MultiPlayerGameMode.destroyBlock}) while the block
 * state is still in the world. A feature that needs breaks registers here instead of adding a
 * second hook.
 *
 * <p>{@code destroyBlock} is where both vanilla break paths end - an instant break from
 * {@code startDestroyBlock} and finished mining progress from {@code continueDestroyBlock} - so it
 * sees every block the client itself breaks. It does not see blocks the server removes for you (the
 * rest of a sugar cane stalk, a dicer's extra blocks).
 *
 * <p>Listeners run on the render thread, once per break, and must be cheap. One that throws is
 * logged and skipped so it cannot take the others, or the break itself, down with it.
 */
public final class BlockBreakEvents {

    /** Receives one broken block. */
    @FunctionalInterface
    public interface Listener {
        void onBlockBroken(BlockPos pos, BlockState state);
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private BlockBreakEvents() {
    }

    /**
     * Adds a listener. Register a lambda that calls the feature's {@code getInstance()} inside it,
     * so the feature's class is not loaded at mod init.
     */
    public static void register(Listener listener) {
        LISTENERS.add(listener);
    }

    /** Called by the break hook only. */
    public static void fire(BlockPos pos, BlockState state) {
        for (Listener listener : LISTENERS) {
            try {
                listener.onBlockBroken(pos, state);
            } catch (RuntimeException broken) {
                SkyblockSimplifiedSBS.LOGGER.warn("[SBS] block-break listener failed: {}",
                        broken.toString());
            }
        }
    }
}
