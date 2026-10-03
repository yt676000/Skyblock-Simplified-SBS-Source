/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.storage;

import net.minecraft.core.BlockPos;

/**
 * Remembers the block the player last right-clicked, so a container screen that opens straight after
 * can be attributed to it.
 *
 * <p>Island chests all share the title "Chest", which makes the title useless as an identity. The
 * block position is the only thing that distinguishes them — and the only moment the client knows it
 * is the interaction itself, because once the menu is open the game has stopped ray-casting at the
 * world. So {@code BlockInteractMixin} records the position on the click and
 * {@link StorageIndex} reads it back when the screen appears, which is what lets a chest keep the
 * same number ("Chest 12") all session.
 *
 * <p>The record expires quickly: a position from a click a minute ago must never be pinned onto an
 * unrelated menu that happens to open later.
 */
public final class BlockInteractTracker {

    /** How long after the click a screen may still be attributed to that block. */
    private static final long FRESHNESS_MS = 2_000L;

    private static volatile BlockPos lastBlock;
    private static volatile long lastTime;

    private BlockInteractTracker() {
    }

    /** Called from the interaction mixin for every block right-click. */
    public static void record(BlockPos pos) {
        lastBlock = pos == null ? null : pos.immutable();
        lastTime = System.currentTimeMillis();
    }

    /** The block clicked in the last couple of seconds, or {@code null} if that was too long ago. */
    public static BlockPos recentBlock() {
        if (lastBlock == null || System.currentTimeMillis() - lastTime > FRESHNESS_MS) {
            return null;
        }
        return lastBlock;
    }
}
