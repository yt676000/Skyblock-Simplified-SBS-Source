/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */
package sbs.modid.client.dungeons.run.render;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.ints.IntSets;

import java.util.List;
import java.util.function.ToIntFunction;

/**
 * The entity-id snapshot the outline mixin probes. Built from the starred list the tracker
 * publishes - which already holds only mobs that passed the line-of-sight test - so the outline can
 * never mark a mob the hitbox would not. Kept free of the level so it is unit-tested.
 */
final class StarredMobIds {

    private StarredMobIds() {
    }

    /** An immutable set of the ids of {@code mobs}; the shared empty set for an empty list. */
    static <T> IntSet of(List<T> mobs, ToIntFunction<? super T> id) {
        if (mobs.isEmpty()) {
            return IntSets.EMPTY_SET;
        }
        IntOpenHashSet ids = new IntOpenHashSet(mobs.size());
        for (int i = 0, n = mobs.size(); i < n; i++) {
            ids.add(id.applyAsInt(mobs.get(i)));
        }
        return IntSets.unmodifiable(ids);
    }
}
