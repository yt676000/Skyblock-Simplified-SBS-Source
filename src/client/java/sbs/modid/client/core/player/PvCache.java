/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.player;

import sbs.modid.SkyblockSimplifiedSBS;

import java.lang.ref.SoftReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The Player Viewer's in-memory cache of other players' computed data: the profile list, the
 * computed profile view, museum and status. Never written to disk - it is someone else's data and
 * has no reason to outlive the game.
 *
 * <p>Only computed, validated results are stored ({@link Loaded#cacheable}); errors, refusals and the
 * minimal fallback view never are. An entry is fresh for {@link #TTL_MS} from its fetch.
 *
 * <p>Bounded so it can never strain a small machine:
 * <ul>
 *   <li>at most {@link #MAX_PLAYERS} players, least recently used first out;</li>
 *   <li>a byte budget of {@code min(16 MiB, 2 % of the max heap)}, by an estimate taken once when
 *       an entry is stored; an entry above half the budget is returned but not stored;</li>
 *   <li>entries above {@link #SOFT_BYTES} are held through a {@link SoftReference}, so the JVM may
 *       reclaim them under memory pressure; a cleared reference is a miss;</li>
 *   <li>when free heap is below {@link #LOW_MEMORY_FRACTION} at store time, everything is dropped
 *       and nothing is stored until it recovers. Checked only when storing.</li>
 * </ul>
 *
 * <p>One load per key at a time: a second request for a key that is being fetched waits for that
 * fetch instead of sending its own. Thread-safe; loads run on the caller's thread (the
 * {@code SBS-PlayerViewer} executor in production).
 */
public final class PvCache {

    /** How long a fetched result is served without asking the server again. */
    public static final long TTL_MS = 5L * 60 * 1000;
    /** How long the refresh button stays disabled per player after use. */
    public static final long REFRESH_COOLDOWN_MS = 10_000L;
    public static final int MAX_PLAYERS = 16;
    static final long MAX_BUDGET_BYTES = 16L * 1024 * 1024;
    static final double BUDGET_HEAP_FRACTION = 0.02;
    static final long SOFT_BYTES = 512L * 1024;
    static final double LOW_MEMORY_FRACTION = 0.10;

    /** The heap figures the guard reads (production: {@link Runtime}). */
    public interface Memory {
        long max();

        long total();

        long free();

        Memory RUNTIME = new Memory() {
            @Override
            public long max() {
                return Runtime.getRuntime().maxMemory();
            }

            @Override
            public long total() {
                return Runtime.getRuntime().totalMemory();
            }

            @Override
            public long free() {
                return Runtime.getRuntime().freeMemory();
            }
        };
    }

    /** What a load produced: the value, whether it may be stored, its size and its fetch time. */
    public record Loaded<T>(T value, boolean cacheable, long sizeBytes, long fetchedAtMs) {
        public static <T> Loaded<T> uncached(T value) {
            return new Loaded<>(value, false, 0, 0);
        }
    }

    /** Fetches a value on a miss. */
    @FunctionalInterface
    public interface Loader<T> {
        Loaded<T> load() throws Exception;
    }

    /** A served value and the time it was fetched (server time when the relay said so). */
    public record Hit<T>(T value, long fetchedAtMs, boolean fromCache) {
    }

    private record Entry(Object strong, SoftReference<Object> soft, long sizeBytes, long storedAtMs,
                         long fetchedAtMs) {
        Object value() {
            return strong != null ? strong : soft.get();
        }
    }

    private final LongSupplier clock;
    private final Memory memory;
    private final BooleanSupplier consentGranted;
    private final Supplier<String> credential;

    /** player uuid -> key -> entry; access-ordered so the eldest player goes first. */
    private final LinkedHashMap<String, Map<String, Entry>> players = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<String, CompletableFuture<Hit<?>>> inFlight = new HashMap<>();
    private final Map<String, Long> refreshedAt = new HashMap<>();
    private long totalBytes;
    private String boundCredential;
    private boolean lowMemory;
    private boolean loggedBudget;
    private boolean loggedLowMemory;
    private long closedAtMs = -1;

    PvCache(LongSupplier clock, Memory memory, BooleanSupplier consentGranted, Supplier<String> credential) {
        this.clock = clock;
        this.memory = memory;
        this.consentGranted = consentGranted;
        this.credential = credential;
    }

    // ------------------------------------------------------------------ keys

    static String listKey() {
        return "list";
    }

    static String viewKey(String profileId) {
        return "view:" + profileId;
    }

    static String museumKey(String profileId) {
        return "museum:" + profileId;
    }

    static String statusKey() {
        return "status";
    }

    // ------------------------------------------------------------------ lookup

    /**
     * The cached value for {@code key} of {@code player}, or the result of {@code loader}. Concurrent
     * calls for the same key share one load. Exceptions from the loader reach every waiter and are
     * never cached.
     */
    @SuppressWarnings("unchecked")
    public <T> Hit<T> get(String player, String key, Loader<T> loader) throws Exception {
        String flightKey = player + "|" + key;
        CompletableFuture<Hit<?>> mine;
        CompletableFuture<Hit<?>> theirs;
        synchronized (this) {
            validate();
            Entry entry = entry(player, key);
            if (entry != null) {
                return new Hit<>((T) entry.value(), entry.fetchedAtMs(), true);
            }
            theirs = inFlight.get(flightKey);
            if (theirs == null) {
                mine = new CompletableFuture<>();
                inFlight.put(flightKey, mine);
            } else {
                mine = null;
            }
        }
        if (theirs != null) {
            try {
                return (Hit<T>) theirs.get();
            } catch (ExecutionException e) {
                throw e.getCause() instanceof Exception cause ? cause : e;
            }
        }
        try {
            Loaded<T> loaded = loader.load();
            Hit<T> hit = new Hit<>(loaded.value(), loaded.fetchedAtMs(), false);
            if (loaded.cacheable()) {
                store(player, key, loaded);
            }
            mine.complete(hit);
            return hit;
        } catch (Exception e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            synchronized (this) {
                inFlight.remove(flightKey, mine);
            }
        }
    }

    /** The fetch time of a fresh cached entry, or -1. Does not count as a use for the LRU order. */
    public synchronized long fetchedAt(String player, String key) {
        Map<String, Entry> entries = players.get(player);
        Entry entry = entries == null ? null : entries.get(key);
        return entry != null && fresh(entry) && entry.value() != null ? entry.fetchedAtMs() : -1;
    }

    private Entry entry(String player, String key) {
        Map<String, Entry> entries = players.get(player);
        if (entries == null) {
            return null;
        }
        Entry entry = entries.get(key);
        if (entry == null) {
            return null;
        }
        if (!fresh(entry) || entry.value() == null) {
            // Expired, or a soft reference the JVM reclaimed: a miss either way.
            remove(entries, key);
            if (entries.isEmpty()) {
                players.remove(player);
            }
            return null;
        }
        return entry;
    }

    private boolean fresh(Entry entry) {
        return clock.getAsLong() - entry.storedAtMs() < TTL_MS;
    }

    /** Drops everything when consent is gone, the credential changed or the viewer sat closed past the TTL. */
    private void validate() {
        if (!consentGranted.getAsBoolean()) {
            clearLocked();
            return;
        }
        String now = credential.get();
        if (boundCredential != null && !boundCredential.equals(now)) {
            clearLocked();
        }
        boundCredential = now;
        if (closedAtMs >= 0 && clock.getAsLong() - closedAtMs > TTL_MS) {
            clearLocked();
        }
        closedAtMs = -1;
    }

    // ------------------------------------------------------------------ store

    private synchronized void store(String player, String key, Loaded<?> loaded) {
        if (!consentGranted.getAsBoolean()) {
            return;
        }
        long max = memory.max();
        long used = memory.total() - memory.free();
        if (max > 0 && max - used < max * LOW_MEMORY_FRACTION) {
            if (!loggedLowMemory) {
                loggedLowMemory = true;
                SkyblockSimplifiedSBS.LOGGER.debug("[SBS][PV] Low free heap: Player Viewer cache cleared and paused.");
            }
            clearLocked();
            lowMemory = true;
            return;
        }
        lowMemory = false;
        long budget = budget();
        long size = Math.max(1, loaded.sizeBytes());
        if (size > budget / 2) {
            return;     // shown, never stored
        }
        Map<String, Entry> entries = players.computeIfAbsent(player, p -> new HashMap<>());
        remove(entries, key);
        boolean evicted = false;
        while (totalBytes + size > budget || players.size() > MAX_PLAYERS) {
            if (!evictEldest(player)) {
                break;
            }
            evicted = true;
        }
        if (evicted && !loggedBudget) {
            loggedBudget = true;
            SkyblockSimplifiedSBS.LOGGER.debug("[SBS][PV] Player Viewer cache at its memory budget; evicting oldest.");
        }
        if (totalBytes + size > budget) {
            return;
        }
        Object value = loaded.value();
        Entry entry = size > SOFT_BYTES
                ? new Entry(null, new SoftReference<>(value), size, clock.getAsLong(), loaded.fetchedAtMs())
                : new Entry(value, null, size, clock.getAsLong(), loaded.fetchedAtMs());
        entries.put(key, entry);
        totalBytes += size;
    }

    /** Evicts the least recently used player other than {@code keep}; false when there is none. */
    private boolean evictEldest(String keep) {
        Iterator<Map.Entry<String, Map<String, Entry>>> it = players.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Map<String, Entry>> eldest = it.next();
            if (eldest.getKey().equals(keep)) {
                continue;
            }
            for (Entry e : eldest.getValue().values()) {
                totalBytes -= e.sizeBytes();
            }
            it.remove();
            return true;
        }
        // Only the player being stored is left: drop its oldest entries.
        Map<String, Entry> own = players.get(keep);
        if (own == null || own.isEmpty()) {
            return false;
        }
        String oldest = null;
        long at = Long.MAX_VALUE;
        for (Map.Entry<String, Entry> e : own.entrySet()) {
            if (e.getValue().storedAtMs() < at) {
                at = e.getValue().storedAtMs();
                oldest = e.getKey();
            }
        }
        remove(own, oldest);
        return true;
    }

    private void remove(Map<String, Entry> entries, String key) {
        Entry old = entries.remove(key);
        if (old != null) {
            totalBytes -= old.sizeBytes();
        }
    }

    /** {@code min(16 MiB, 2 % of the max heap)}. */
    long budget() {
        long max = memory.max();
        long fraction = max > 0 && max != Long.MAX_VALUE ? (long) (max * BUDGET_HEAP_FRACTION) : MAX_BUDGET_BYTES;
        return Math.max(1, Math.min(MAX_BUDGET_BYTES, fraction));
    }

    // ------------------------------------------------------------------ refresh / lifecycle

    /**
     * Drops every entry of {@code player} so the next lookups fetch again. Returns false (and drops
     * nothing) while the per-player cooldown runs.
     */
    public synchronized boolean refresh(String player) {
        long now = clock.getAsLong();
        if (refreshCooldownMs(player) > 0) {
            return false;
        }
        refreshedAt.put(player, now);
        Map<String, Entry> entries = players.remove(player);
        if (entries != null) {
            for (Entry e : entries.values()) {
                totalBytes -= e.sizeBytes();
            }
        }
        return true;
    }

    /** Milliseconds until {@code player} may be refreshed again; 0 when it may. */
    public synchronized long refreshCooldownMs(String player) {
        Long at = refreshedAt.get(player);
        return at == null ? 0 : Math.max(0, at + REFRESH_COOLDOWN_MS - clock.getAsLong());
    }

    /** The viewer was closed: if it stays closed past the TTL, everything is dropped. */
    public synchronized void viewerClosed() {
        closedAtMs = clock.getAsLong();
    }

    /** The viewer was opened again: the closed timer stops, after dropping what it outlived. */
    public synchronized void viewerOpened() {
        validate();
    }

    /** Drops everything if the viewer has been closed for longer than the TTL. */
    public synchronized void expireIfClosed() {
        if (closedAtMs >= 0 && clock.getAsLong() - closedAtMs > TTL_MS) {
            clearLocked();
            closedAtMs = -1;
        }
    }

    public synchronized void clear() {
        clearLocked();
    }

    private void clearLocked() {
        players.clear();
        totalBytes = 0;
    }

    // ------------------------------------------------------------------ for tests

    synchronized long totalBytes() {
        return totalBytes;
    }

    synchronized int playerCount() {
        return players.size();
    }

    /** Simulates the JVM reclaiming every softly held entry. */
    synchronized void clearSoftReferences() {
        for (Map<String, Entry> entries : players.values()) {
            for (Entry e : entries.values()) {
                if (e.soft() != null) {
                    e.soft().clear();
                }
            }
        }
    }

    synchronized boolean pausedForLowMemory() {
        return lowMemory;
    }

    synchronized List<String> playersEldestFirst() {
        return new ArrayList<>(players.keySet());
    }
}
