/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.dev;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.SBSFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Where the Layout Recorder keeps what it has seen: {@code Development_Stuff/layouts/}, one JSON
 * file per unique layout plus {@code index.json}.
 *
 * <p><b>Nothing touches the disk on the render thread.</b> A new signature queues its file write on
 * a single background thread; a signature already seen only bumps counters in memory, flushed to
 * {@code index.json} every few minutes and at shutdown. The index is read once, lazily, the first
 * time the recorder is used - so with the recorder off this class is never even loaded.
 *
 * <p><b>Variants.</b> A menu whose coarse signature (slot → item id) is known but whose fine one is
 * not is stored beside its base as {@code <base>-v2.json}, {@code -v3} ..., with the slots that
 * changed. Capped per base; at the cap one warning names the screen, which then needs a better
 * normaliser rule rather than more files.
 *
 * <p><b>Size guard.</b> Past the configured total, no new file is written (counters still count)
 * and one line is logged.
 */
final class LayoutStore {

    private static final long FLUSH_MS = 3 * 60_000L;
    /** A new origin for an already-known layout is written this long after it was seen (debounce). */
    private static final long ORIGIN_DEBOUNCE_MS = 2_000L;

    /** One index entry. Field names are the file format. */
    static final class Entry {
        String file;
        String category;
        long firstSeen;
        long lastSeen;
        int timesSeen;
        String where;
        String version;
        String coarse;
        String variantOf;
    }

    private final Map<String, Entry> index = new ConcurrentHashMap<>();
    /** coarse hash → the fine hash of its base file. */
    private final Map<String, String> baseByCoarse = new ConcurrentHashMap<>();
    /** base fine hash → variants written so far. */
    private final Map<String, Integer> variants = new ConcurrentHashMap<>();
    /** base fine hash → its per-slot fine forms, for the variant diff. */
    private final Map<String, List<String>> baseParts = new ConcurrentHashMap<>();
    private final Set<String> cappedWarned = new HashSet<>();
    private final AtomicLong totalBytes = new AtomicLong();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SBS-LayoutRecorder");
        t.setDaemon(true);
        return t;
    });

    /** layout file -> origins seen since the last origin flush, merged into the file on the IO thread. */
    private final Map<String, List<JsonObject>> pendingOrigins = new HashMap<>();
    /** layout file -> origin keys seen this session, so only a NEW origin triggers the quick write. */
    private final Map<String, Set<String>> sessionOriginKeys = new HashMap<>();
    private final LayoutGraph graph = new LayoutGraph();
    private volatile boolean graphDirty;
    private long urgentOriginsSince = -1;
    private volatile int originCap = 16;

    private volatile boolean loaded;
    private volatile boolean dirty;
    private volatile boolean fullWarned;
    private long lastFlushAt;

    static Path dir() {
        return SBSFiles.developmentDir().resolve("layouts");
    }

    // ------------------------------------------------------------------ recording

    /**
     * One capture.
     *
     * @param category   {@code menus}, {@code tab}, {@code scoreboard}, {@code actionbar}
     * @param group      the sub-folder within it (a title slug, a widget key), or {@code null}
     * @param fine       the fine canonical string
     * @param coarse     the coarse canonical string, or {@code null} when the kind has no variants
     * @param parts      per-slot fine forms (menus only), for the variant diff; may be empty
     * @param raw        the redacted raw example, stored once with the first file
     * @param origin     where and how the screen was opened ({@link LayoutOrigins}), or {@code null}
     * @param title      the redacted screen title, for the graph; {@code null} without an origin
     * @param cap        origins kept per file
     */
    void record(String category, String group, String fine, String coarse, List<String> parts,
                JsonElement raw, String where, String version, int variantCap, long maxBytes,
                JsonObject origin, String title, int cap) {
        ensureLoaded();
        if (cap > 0) {
            originCap = cap;
        }
        long now = System.currentTimeMillis();
        String hash = LayoutSignature.hash(fine);
        Entry seen = index.get(hash);
        if (seen != null) {
            seen.lastSeen = now;
            seen.timesSeen++;
            dirty = true;
            if (origin != null) {
                queueOrigin(seen.file, origin, title, now);
            }
            maybeFlush(now);
            return;
        }
        String coarseHash = coarse == null ? null : LayoutSignature.hash(coarse);
        String base = coarseHash == null ? null : baseByCoarse.get(coarseHash);
        String folder = category + (group == null ? "" : "/" + group);
        String fileName;
        JsonArray changed = null;
        if (base != null) {
            int n = variants.merge(base, 1, Integer::sum);
            if (n > variantCap) {
                if (cappedWarned.add(base)) {
                    SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Layouts] {} hit the variant cap ({}) - this "
                            + "screen puts live data where the normaliser does not see it; add a rule "
                            + "to LayoutSignature", folder, variantCap);
                }
                return;
            }
            fileName = base + "-v" + (n + 1) + ".json";
            changed = new JsonArray();
            List<String> baseSlots = baseParts.get(base);
            if (baseSlots != null) {
                for (int slot : LayoutCanon.changedSlots(baseSlots, parts)) {
                    changed.add(slot);
                }
            }
        } else {
            fileName = hash + ".json";
            if (coarseHash != null) {
                baseByCoarse.put(coarseHash, hash);
                baseParts.put(hash, List.copyOf(parts));
            }
        }
        if (totalBytes.get() >= maxBytes) {
            if (!fullWarned) {
                fullWarned = true;
                SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Layouts] {} is over its size limit ({} MB) - "
                        + "no new layouts are recorded", dir(), maxBytes / (1024 * 1024));
            }
            return;
        }
        Entry entry = new Entry();
        entry.file = folder + "/" + fileName;
        entry.category = category;
        entry.firstSeen = now;
        entry.lastSeen = now;
        entry.timesSeen = 1;
        entry.where = where;
        entry.version = version;
        entry.coarse = coarseHash;
        entry.variantOf = base;
        index.put(hash, entry);
        dirty = true;

        JsonObject file = new JsonObject();
        file.addProperty("signature", fine);
        file.addProperty("hash", hash);
        if (base != null) {
            file.addProperty("variantOf", base);
            file.add("changedSlots", changed);
        }
        file.addProperty("firstSeen", now);
        file.addProperty("where", where);
        file.addProperty("modVersion", version);
        if (origin != null) {
            JsonArray origins = new JsonArray();
            origins.add(origin);
            file.add("origins", origins);
            synchronized (this) {
                sessionOriginKeys.computeIfAbsent(entry.file, k -> new HashSet<>()).add(LayoutOrigins.key(origin));
                graphDirty |= graph.add(origin.getAsJsonArray("path"), title, entry.file);
            }
        }
        file.add("example", raw);
        String json = SBSFiles.GSON.toJson(file);
        Path path = dir().resolve(entry.file);
        io.execute(() -> write(path, json));
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Layouts] new {} layout -> {}", category, entry.file);
        maybeFlush(now);
    }

    private void write(Path path, String json) {
        try {
            Files.createDirectories(path.getParent());
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            Files.write(path, bytes);
            totalBytes.addAndGet(bytes.length);
            sbs.modid.client.core.perf.Perf.countDiskWrite();
        } catch (IOException e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Layouts] could not write {}: {}", path, e.toString());
        }
    }

    // ------------------------------------------------------------------ origins

    /** Remembers an opening of a known layout; a new origin is written after a short debounce. */
    private synchronized void queueOrigin(String file, JsonObject origin, String title, long now) {
        pendingOrigins.computeIfAbsent(file, k -> new ArrayList<>()).add(origin);
        graphDirty |= graph.add(origin.getAsJsonArray("path"), title, file);
        boolean isNew = sessionOriginKeys.computeIfAbsent(file, k -> new HashSet<>()).add(LayoutOrigins.key(origin));
        if (isNew && urgentOriginsSince < 0) {
            urgentOriginsSince = now;
        }
    }

    /** Client tick (through the recorder): writes queued NEW origins once the debounce has passed. */
    void tick(long now) {
        if (urgentOriginsSince >= 0 && now - urgentOriginsSince >= ORIGIN_DEBOUNCE_MS) {
            flushOrigins();
        }
    }

    /** Hands the queued origins to the IO thread, which merges them into their files. */
    private void flushOrigins() {
        Map<String, List<JsonObject>> batch;
        synchronized (this) {
            urgentOriginsSince = -1;
            if (pendingOrigins.isEmpty()) {
                return;
            }
            batch = new HashMap<>(pendingOrigins);
            pendingOrigins.clear();
        }
        int cap = originCap;
        io.execute(() -> batch.forEach((file, origins) -> mergeOrigins(file, origins, cap)));
    }

    /** IO thread: reads one layout file, merges the origins in, writes it back. */
    private void mergeOrigins(String file, List<JsonObject> origins, int cap) {
        Path path = dir().resolve(file);
        try {
            if (!Files.isRegularFile(path)) {
                return;   // deleted (cleared) since: nothing to add to
            }
            JsonObject json = SBSFiles.GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), JsonObject.class);
            if (json == null) {
                return;
            }
            JsonArray existing = LayoutOrigins.originsOf(json);
            for (JsonObject origin : origins) {
                LayoutOrigins.merge(existing, origin, cap);
            }
            Files.writeString(path, SBSFiles.GSON.toJson(json), StandardCharsets.UTF_8);
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Layouts] could not add origins to {}: {}", file, e.toString());
        }
    }

    // ------------------------------------------------------------------ index

    private void maybeFlush(long now) {
        if (dirty && now - lastFlushAt >= FLUSH_MS) {
            flush();
        }
    }

    /** Writes index.json if anything changed; queued on the IO thread. */
    void flush() {
        if (!loaded) {
            return;
        }
        flushOrigins();
        flushGraph();
        if (!dirty) {
            return;
        }
        dirty = false;
        lastFlushAt = System.currentTimeMillis();
        String json = SBSFiles.GSON.toJson(Map.copyOf(index));
        io.execute(() -> {
            try {
                Files.createDirectories(dir());
                Files.writeString(dir().resolve("index.json"), json, StandardCharsets.UTF_8);
            } catch (IOException e) {
                SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Layouts] could not write index.json: {}", e.toString());
            }
        });
    }

    /** Writes graph.json on the IO thread if it learned anything. */
    private void flushGraph() {
        String json;
        synchronized (this) {
            if (!graphDirty) {
                return;
            }
            graphDirty = false;
            json = SBSFiles.GSON.toJson(graph.toJson());
        }
        io.execute(() -> {
            try {
                Files.createDirectories(dir());
                Files.writeString(dir().resolve("graph.json"), json, StandardCharsets.UTF_8);
            } catch (IOException e) {
                SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Layouts] could not write graph.json: {}", e.toString());
            }
        });
    }

    private synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        Runtime.getRuntime().addShutdownHook(new Thread(this::flushNow, "SBS-LayoutRecorder-flush"));
        Path indexFile = dir().resolve("index.json");
        try {
            if (Files.isRegularFile(indexFile)) {
                JsonObject root = SBSFiles.GSON.fromJson(Files.readString(indexFile, StandardCharsets.UTF_8),
                        JsonObject.class);
                if (root != null) {
                    for (var e : root.entrySet()) {
                        Entry entry = SBSFiles.GSON.fromJson(e.getValue(), Entry.class);
                        if (entry == null) {
                            continue;
                        }
                        index.put(e.getKey(), entry);
                        if (entry.coarse != null && entry.variantOf == null) {
                            baseByCoarse.put(entry.coarse, e.getKey());
                        }
                        if (entry.variantOf != null) {
                            variants.merge(entry.variantOf, 1, Integer::sum);
                        }
                    }
                }
            }
            Path graphFile = dir().resolve("graph.json");
            if (Files.isRegularFile(graphFile)) {
                graph.load(SBSFiles.GSON.fromJson(Files.readString(graphFile, StandardCharsets.UTF_8),
                        JsonObject.class));
            }
            if (Files.isDirectory(dir())) {
                try (Stream<Path> files = Files.walk(dir())) {
                    totalBytes.set(files.filter(Files::isRegularFile).mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException e) {
                            return 0L;
                        }
                    }).sum());
                }
            }
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Layouts] could not read the index ({}) - starting "
                    + "a new one; existing files are kept", e.toString());
        }
    }

    /** Shutdown: write the index synchronously - the IO thread is a daemon and may already be gone. */
    private void flushNow() {
        if (!loaded) {
            return;
        }
        // Shutdown: the IO thread may be gone, so queued origins and the graph are written here.
        Map<String, List<JsonObject>> batch;
        String graphJson;
        synchronized (this) {
            batch = new HashMap<>(pendingOrigins);
            pendingOrigins.clear();
            graphJson = graphDirty ? SBSFiles.GSON.toJson(graph.toJson()) : null;
            graphDirty = false;
        }
        batch.forEach((file, origins) -> mergeOrigins(file, origins, originCap));
        if (graphJson != null) {
            try {
                Files.writeString(dir().resolve("graph.json"), graphJson, StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                // shutting down; nothing to tell
            }
        }
        if (!dirty) {
            return;
        }
        try {
            Files.createDirectories(dir());
            Files.writeString(dir().resolve("index.json"), SBSFiles.GSON.toJson(Map.copyOf(index)),
                    StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // shutting down; nothing to tell
        }
    }

    // ------------------------------------------------------------------ clear

    /** Deletes every recorded layout and forgets the index. Confirmed by the caller. */
    void clear() {
        index.clear();
        baseByCoarse.clear();
        variants.clear();
        baseParts.clear();
        cappedWarned.clear();
        synchronized (this) {
            pendingOrigins.clear();
            sessionOriginKeys.clear();
            graph.clear();
            graphDirty = false;
        }
        fullWarned = false;
        dirty = false;
        loaded = true;   // an empty index is the truth now; do not re-read the old file
        io.execute(() -> {
            Path dir = dir();
            if (!Files.isDirectory(dir)) {
                return;
            }
            try (Stream<Path> files = Files.walk(dir)) {
                files.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // a file in use is left; the next clear gets it
                    }
                });
            } catch (IOException e) {
                SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Layouts] clear failed: {}", e.toString());
            }
            totalBytes.set(0);
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Layouts] recorded layouts cleared");
        });
    }
}
