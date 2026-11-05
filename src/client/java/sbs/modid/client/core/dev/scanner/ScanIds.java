/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.dev.scanner;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

/**
 * Short, stable session ids for entities: the first sight of an entity gets {@code e17} and a full
 * {@code spawn} record, and every later event names it by that id alone instead of repeating the
 * entity. The counter never restarts within a session, so an id is never reused - not even after a
 * world change, where Minecraft starts handing out the same numeric ids again ({@link #clear}).
 *
 * <p>Client thread only. Pure, unit-tested.
 */
final class ScanIds {

    private final Int2ObjectOpenHashMap<String> byEntity = new Int2ObjectOpenHashMap<>();
    private int next = 1;

    /** The id of {@code entityId}, or {@code null} when it has none yet. */
    String get(int entityId) {
        return byEntity.get(entityId);
    }

    /** The id of {@code entityId}, minting the next one on first sight. */
    String assign(int entityId) {
        String id = byEntity.get(entityId);
        if (id == null) {
            id = "e" + next++;
            byEntity.put(entityId, id);
        }
        return id;
    }

    /** The entity is gone: its numeric id may be reused for another one. */
    void forget(int entityId) {
        byEntity.remove(entityId);
    }

    /** World change: every numeric id is meaningless now. The counter keeps going. */
    void clear() {
        byEntity.clear();
    }

    /** How many ids were handed out. */
    int minted() {
        return next - 1;
    }
}
