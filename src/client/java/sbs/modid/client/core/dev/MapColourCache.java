/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.dev;

import java.util.Arrays;

/**
 * Remembers the map colours the last tile analysis ran on, so {@link RoomMapReader#readSnapshot} can
 * skip the analysis while the map did not change. A full copy compared with {@link Arrays#equals}
 * (vectorised, 16 KB) instead of a hash: no collisions, and allocation-free once the copy exists.
 */
final class MapColourCache {

    private final byte[] seen;
    private Object source;
    private boolean valid;

    MapColourCache(int size) {
        seen = new byte[size];
    }

    /**
     * Whether {@code colours} (owned by {@code owner}) differ from the last call; a change is
     * recorded, so the next call with the same content returns {@code false}.
     */
    boolean changed(Object owner, byte[] colours) {
        if (valid && owner == source && Arrays.equals(seen, colours)) {
            return false;
        }
        System.arraycopy(colours, 0, seen, 0, seen.length);
        source = owner;
        valid = true;
        return true;
    }

    void invalidate() {
        valid = false;
        source = null;
    }
}
