/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.render;

import sbs.modid.client.core.perf.CacheCounter;
import sbs.modid.client.core.perf.Perf;

import java.util.Objects;
import java.util.function.Function;

/**
 * A model derived from a key, rebuilt only when the key changes.
 *
 * <p>For the render path: a card that draws the same rows every frame keys them on whatever they are
 * built from (a {@link sbs.modid.client.core.scoreboard.ScoreboardSnapshot}, a revision, a config
 * value) and pays for the build once per change instead of once per frame. A hit costs an identity
 * check - or {@code equals} when the key is a different but equal object - and allocates nothing.
 *
 * <p>Render thread only, no locking. The key and the model are stored as one immutable entry in one
 * field, so even a stray call from another thread can only cause a redundant rebuild, never a model
 * paired with the wrong key.
 *
 * @param <K> what the model is built from; compared with {@link Objects#equals}
 * @param <M> the derived model
 */
public final class CachedModel<K, M> {

    private record Entry<K, M>(K key, M model) {
    }

    private final Function<? super K, ? extends M> builder;
    /** {@code null} for an unnamed cache: nothing counted. */
    private final CacheCounter counter;
    private Entry<K, M> entry;

    /** {@code builder} turns a key into its model; it is called only on a miss. */
    public CachedModel(Function<? super K, ? extends M> builder) {
        this.builder = Objects.requireNonNull(builder, "builder");
        this.counter = null;
    }

    /**
     * A cache whose hits, misses and invalidations show in {@code /sbs perf} under {@code name}
     * (a constant; the same name shares one counter). Create it once, in a field.
     *
     * <p><b>Client thread only:</b> {@link #get} and {@link #invalidate} on a named cache are counted
     * only from the client (render) thread; calls from any other thread still work but are not
     * counted (see {@link CacheCounter}). A cache read off-thread should use the unnamed constructor.
     */
    public CachedModel(String name, Function<? super K, ? extends M> builder) {
        this.builder = Objects.requireNonNull(builder, "builder");
        this.counter = Perf.counter(Objects.requireNonNull(name, "name"));
    }

    /** The model for {@code key}: the cached one when the key is unchanged, otherwise a fresh build. */
    public M get(K key) {
        Entry<K, M> cached = entry;
        if (cached != null && (cached.key() == key || Objects.equals(cached.key(), key))) {
            if (counter != null) {
                counter.hit();
            }
            return cached.model();
        }
        if (counter != null) {
            counter.miss();
        }
        M model = builder.apply(key);
        entry = new Entry<>(key, model);
        return model;
    }

    /** Drops the cached model, so the next {@link #get} builds whatever the key. */
    public void invalidate() {
        invalidate(CacheCounter.Reason.OTHER);
    }

    /** {@link #invalidate()}, counted under {@code reason}. */
    public void invalidate(CacheCounter.Reason reason) {
        if (counter != null && entry != null) {
            counter.invalidate(reason);
        }
        entry = null;
    }
}
