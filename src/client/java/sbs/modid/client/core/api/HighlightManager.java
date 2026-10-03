/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.api;

import java.util.Collection;
import java.util.Set;

/**
 * Holds the set of slot indices that should be visually highlighted in the currently
 * open container.
 *
 * <p>Written by the HTTP thread ({@code POST /highlight}) and read by the render
 * thread ({@code ApiHighlightDecorator}). Thread-safe via a {@code volatile}
 * reference to an immutable {@link Set} – writers replace the whole set, readers
 * always see a consistent snapshot.
 *
 * <p>This drives the in-game highlight overlay used for future tutorials such as
 * "click slot 13" or "click the Booster Cookie".
 */
public final class HighlightManager {

    private static final HighlightManager INSTANCE = new HighlightManager();

    public static HighlightManager getInstance() {
        return INSTANCE;
    }

    private volatile Set<Integer> highlighted = Set.of();

    private HighlightManager() {
    }

    /** Replaces the highlighted slots (empty / null clears them). */
    public void setHighlighted(Collection<Integer> slots) {
        this.highlighted = (slots == null || slots.isEmpty()) ? Set.of() : Set.copyOf(slots);
    }

    public void clear() {
        this.highlighted = Set.of();
    }

    /** Current highlighted slot indices (immutable snapshot). */
    public Set<Integer> getHighlighted() {
        return highlighted;
    }
}
