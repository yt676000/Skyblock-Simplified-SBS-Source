/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.data;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.economy.pricehistory.logic.PriceApi;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A dataset that ships bundled, caches on disk, and refreshes from the backend - the shared loading
 * path for every "curated data the server owns" file (Fairy Souls, launch pads, ...).
 *
 * <p><b>Three sources, best wins.</b> In order of appearance, never of authority:
 * <ol>
 *   <li>the copy <b>bundled in the jar</b> – always present, so the feature works offline, on a
 *       fresh install, and when the licence server is down;</li>
 *   <li>the <b>cached</b> copy from the last successful fetch – so a restart does not re-download;</li>
 *   <li>the <b>backend</b> copy, fetched once per session in the background.</li>
 * </ol>
 * Each is accepted only if it passes the schema gate and carries a <i>higher</i> {@code dataVersion}
 * than what is already loaded. That ordering is what makes a failed or slow fetch a non-event: the
 * bundled data is already live before the request is even made.
 *
 * <p><b>Nothing here blocks the game.</b> The bundled and cached reads happen on the calling thread
 * at startup (both are local and small); the network fetch runs on its own daemon thread and simply
 * publishes a better document if it finds one.
 *
 * <p><b>Partial coverage is normal.</b> A file describing three islands out of fifteen is a first
 * pass, not an error - callers ask "what do you have for this island" and get nothing back for the
 * rest, which every consumer already has to handle anyway.
 *
 * @param <T> the parsed document type
 */
public final class VersionedDataStore<T extends VersionedDocument> {

    /** The highest {@code schemaVersion} this build knows how to read. */
    private final int supportedSchema;

    private final String name;
    private final String bundledResource;
    private final String endpoint;
    private final Path cacheFile;
    private final Class<T> type;

    /** The best document loaded so far, or {@code null} when even the bundled copy failed. */
    private volatile T current;

    /** One fetch per session; a second call is a no-op rather than a second request. */
    private final AtomicBoolean fetched = new AtomicBoolean();

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SBS-data-fetch");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * @param name            short label for log lines ("FairySouls")
     * @param bundledResource classpath path of the shipped copy
     * @param cacheFile       where a fetched copy is cached
     * @param endpoint        backend path to fetch from, or {@code null} for bundled-only
     * @param type            the document class Gson parses into
     * @param supportedSchema the highest schema version this build can read
     */
    public VersionedDataStore(String name, String bundledResource, Path cacheFile,
                              String endpoint, Class<T> type, int supportedSchema) {
        this.name = name;
        this.bundledResource = bundledResource;
        this.cacheFile = cacheFile;
        this.endpoint = endpoint;
        this.type = type;
        this.supportedSchema = supportedSchema;
    }

    /** The best document available right now, or {@code null} when nothing loaded. */
    public T get() {
        return current;
    }

    /** The loaded content version, or {@code -1} when nothing loaded (for status lines). */
    public int version() {
        T document = current;
        return document == null ? -1 : document.dataVersion();
    }

    /** Where the live document came from, for the settings status line. */
    public String source() {
        return current == null ? "none" : sourceLabel;
    }

    private volatile String sourceLabel = "none";

    /**
     * Loads bundled then cached, then kicks off the background refresh. Safe to call on client init;
     * returns as soon as the local copies are in memory.
     */
    public void load() {
        accept(readBundled(), "bundled");
        accept(readCached(), "cache");
        refresh();
    }

    /** Starts the background fetch if it has not run yet this session. */
    public void refresh() {
        if (endpoint == null || !fetched.compareAndSet(false, true)) {
            return;
        }
        executor.execute(this::fetch);
    }

    // ------------------------------------------------------------------ sources

    private T readBundled() {
        try (InputStream in = VersionedDataStore.class.getResourceAsStream(bundledResource)) {
            if (in == null) {
                SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Data] {}: no bundled copy at {}",
                        name, bundledResource);
                return null;
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return SBSFiles.GSON.fromJson(reader, type);
            }
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][Data] {}: bundled copy failed to parse", name, e);
            return null;
        }
    }

    private T readCached() {
        try {
            if (!Files.isRegularFile(cacheFile)) {
                return null;
            }
            try (Reader reader = Files.newBufferedReader(cacheFile, StandardCharsets.UTF_8)) {
                return SBSFiles.GSON.fromJson(reader, type);
            }
        } catch (Exception e) {
            // A corrupt cache must never be fatal - the bundled copy is already loaded.
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Data] {}: cache unreadable ({}), ignoring",
                    name, e.toString());
            return null;
        }
    }

    private void fetch() {
        try {
            String json = PriceApi.getInstance().getAuthenticated(endpoint);
            T document = SBSFiles.GSON.fromJson(json, type);
            if (accept(document, "backend")) {
                writeCache(json);
            }
        } catch (Exception e) {
            // Offline, no licence, server down: all normal. The bundled data is already serving.
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Data] {}: refresh skipped ({})",
                    name, e.toString());
        }
    }

    private void writeCache(String json) {
        try {
            Files.createDirectories(cacheFile.getParent());
            Files.writeString(cacheFile, json, StandardCharsets.UTF_8);
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Data] {}: could not write cache", name, e);
        }
    }

    // ------------------------------------------------------------------ gate

    /**
     * Takes {@code candidate} only if it is readable by this build and newer than what is loaded.
     *
     * @return whether it became the live document
     */
    private synchronized boolean accept(T candidate, String from) {
        if (candidate == null) {
            return false;
        }
        if (candidate.schemaVersion() > supportedSchema) {
            // Refusing is the point: parsing a newer schema yields objects with silently missing
            // fields, which is far worse than continuing to serve the copy we understand.
            SkyblockSimplifiedSBS.LOGGER.warn(
                    "[SBS][Data] {}: {} copy is schema v{}, this build reads up to v{} - ignored",
                    name, from, candidate.schemaVersion(), supportedSchema);
            return false;
        }
        if (!candidate.valid()) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Data] {}: {} copy has no usable content", name, from);
            return false;
        }
        if (current != null && candidate.dataVersion() <= current.dataVersion()) {
            return false;
        }
        current = candidate;
        sourceLabel = from;
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Data] {}: using {} copy, data v{}",
                name, from, candidate.dataVersion());
        return true;
    }
}
