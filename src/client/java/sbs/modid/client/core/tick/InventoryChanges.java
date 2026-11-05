/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.tick;

/**
 * When the client last applied a packet that can change an item slot - any container's contents or
 * one slot of it, the player's own inventory included. Fed by {@code ContainerContentMixin} after the
 * thread redirect, so it is written on the client thread only.
 *
 * <p>For tick gates whose feature keeps an inventory baseline: while nothing changed for longer than
 * the feature remembers changes, skipping its diff loses nothing.
 */
public final class InventoryChanges {

    private static long lastChangeAt;
    private static int revision;

    private InventoryChanges() {
    }

    /** A slot may have changed. Client thread. */
    public static void mark() {
        lastChangeAt = System.currentTimeMillis();
        revision++;
    }

    /**
     * Bumped on every slot packet. A menu reader that remembers the value it last read at can skip
     * re-reading while it is unchanged. Client thread.
     */
    public static int revision() {
        return revision;
    }

    /** Whether a slot may have changed within the last {@code windowMs}. */
    public static boolean within(long windowMs) {
        return System.currentTimeMillis() - lastChangeAt < windowMs;
    }
}
