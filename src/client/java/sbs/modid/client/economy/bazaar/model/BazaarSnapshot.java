/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.bazaar.model;

import sbs.modid.client.economy.bazaar.logic.BazaarApiClient;
import sbs.modid.client.economy.bazaar.logic.BazaarSyncService;
/**
 * The single shared client-side store of the current Bazaar snapshot.
 *
 * <p>Every module that needs Bazaar data ({@link BazaarSyncService} for order status,
 * {@link sbs.modid.client.economy.prices.BazaarPriceCache} for tooltip prices, and anything added later)
 * reads it through here instead of firing its own {@code /api/hypixel/bazaar} request. The full
 * snapshot is fetched at most once per {@code maxAge} window and reused by every caller, so N
 * modules collapse into one request stream — the whole point of the store: one client, many
 * consumers, never N duplicate Hypixel pulls per interval.
 *
 * <p><b>Single-flight.</b> {@link #get(long)} is synchronized: a caller whose window is still fresh
 * gets the cached snapshot with no network at all, and when a refresh IS due only one thread
 * fetches while the others block briefly and then see the just-fetched result — never two
 * overlapping pulls of the same data. A failed fetch keeps (and returns) the last good snapshot.
 *
 * <p>All calls happen on the consumers' own background daemon threads, never the client thread.
 *
 * <p><b>Update wave.</b> A refresh is <i>pushed</i> to every registered {@link Listener} the moment it
 * lands, instead of each consumer noticing it on its own next poll. Before, a pull the price cache
 * triggered sat unused until the order sync's next tick, so a consumer could keep serving data from a
 * snapshot that had already been superseded. Now whichever consumer causes the fetch, all of them are
 * handed the new data in the same instant.
 */
public final class BazaarSnapshot {

    /** Receives every freshly fetched snapshot, on the background thread that fetched it. */
    public interface Listener {
        void onBazaarSnapshot(BazaarApiClient.Response response);
    }

    private static final BazaarSnapshot INSTANCE = new BazaarSnapshot();

    /**
     * How often Hypixel publishes a new Bazaar generation. Measured 2026-07-25 over six pulls:
     * {@code lastUpdated} advanced 394182 → 414065 → 434148 → 454183, i.e. every ~20s (and repeated
     * unchanged in between). Used to skip fetches that would return data we already hold.
     */
    private static final long SOURCE_ROTATION_MS = 20_000L;

    private final BazaarApiClient api = new BazaarApiClient();
    private final java.util.List<Listener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile BazaarApiClient.Response cached;
    private volatile long fetchedAt;

    /**
     * Bumped on every successful refresh. Lets a consumer that just called {@link #get(long)} tell
     * "I triggered the fetch, so the wave already delivered this to me" from "the cache was still
     * fresh, no wave fired, I evaluate against the cached copy myself" — which is what keeps the
     * wave from double-processing the same snapshot.
     */
    private volatile long generation;

    private BazaarSnapshot() {
    }

    public static BazaarSnapshot getInstance() {
        return INSTANCE;
    }

    /** Registers a consumer for the update wave. Listeners are held for the process lifetime. */
    public void addListener(Listener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /** Refresh counter; compare across a {@link #get(long)} call to see whether it actually fetched. */
    public long generation() {
        return generation;
    }

    /** Age of the stored snapshot in millis, or {@link Long#MAX_VALUE} before the first pull. */
    public long ageMs() {
        return cached == null ? Long.MAX_VALUE : System.currentTimeMillis() - fetchedAt;
    }

    /**
     * The shared snapshot, reused when the last pull is younger than {@code maxAgeMs}; otherwise one
     * fetch refreshes it for every consumer. Returns the last good snapshot (possibly {@code null}
     * before the first successful pull) when this refresh fails, so a hiccup never blanks the data.
     *
     * @param maxAgeMs how stale this caller tolerates the snapshot to be; a shorter window from any
     *                 one consumer keeps it fresher for all of them (order sync's ~5s drives it, the
     *                 60s price cache then reuses that fresh pull instead of making its own)
     */
    public BazaarApiClient.Response get(long maxAgeMs) {
        BazaarApiClient.Response fresh = refreshIfStale(maxAgeMs);
        if (fresh != null) {
            broadcast(fresh);
        }
        return cached;
    }

    /**
     * The locked half of {@link #get(long)}: fetches only when the window has expired and returns the
     * new response <b>only to the caller that actually fetched it</b> ({@code null} for everyone who
     * was served from cache). Kept separate so the network call and the wave never run while another
     * consumer is blocked on this monitor.
     */
    private synchronized BazaarApiClient.Response refreshIfStale(long maxAgeMs) {
        long now = System.currentTimeMillis();
        if (cached != null) {
            if (now - fetchedAt < maxAgeMs) {
                return null; // a recent pull (this module's or another's) is still fresh - no request
            }
            // Rotation gate: the source publishes a new generation every ~20s, so until the copy we
            // hold reaches that age a fetch would return byte-identical data (measured: two pulls 12s
            // apart carried the same lastUpdated). Gating on the DATA's age rather than a fixed poll
            // interval means we pick up each generation right after it appears without burning a
            // ~440 KB request on a payload we already have.
            long dataAge = now - cached.lastUpdated;
            if (cached.lastUpdated > 0 && dataAge < SOURCE_ROTATION_MS) {
                return null;
            }
        }
        BazaarApiClient.Response fresh = api.fetch();
        if (fresh == null) {
            return null; // failed pull: keep (and keep serving) the last good snapshot
        }
        cached = fresh;
        fetchedAt = now;
        generation++;
        return fresh;
    }

    /**
     * The update wave: hands the new snapshot to every consumer at once. Deliberately runs OUTSIDE
     * the {@code synchronized} block – a listener doing real work (re-evaluating every tracked order)
     * must not hold up another module's {@link #get(long)}. One listener throwing never stops the rest.
     */
    private void broadcast(BazaarApiClient.Response response) {
        for (Listener listener : listeners) {
            try {
                listener.onBazaarSnapshot(response);
            } catch (Throwable t) {
                sbs.modid.SkyblockSimplifiedSBS.LOGGER.error("[SBS][Bazaar] Snapshot listener failed", t);
            }
        }
    }

    /** The last stored snapshot without ever triggering a fetch, or {@code null}. */
    public BazaarApiClient.Response peek() {
        return cached;
    }
}
