/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.terrain;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.SBSFiles;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which island claimed which chunk position on a shared map - the record that proves, or disproves,
 * that two islands are overwriting each other.
 *
 * <p><b>Why this had to become persistent.</b> Islands grouped onto one map are keyed by X/Z alone,
 * so two of them occupying the same position silently overwrite each other and the only symptom is
 * the wrong island appearing in the distance. The detector for that used to live in a session-scoped
 * map, which meant it could only ever notice an overlap between two islands visited in <i>one</i>
 * sitting - and a player who logs in on the Hub today and visits the Park tomorrow would never
 * trigger it. Kept on disk it accumulates across every session until the answer is unambiguous.
 *
 * <p>It also measures each island's <b>bounding box</b>, which is the input any coordinate
 * translation needs: you cannot decide where to move an island until you know how big it is and
 * where it currently sits.
 *
 * <p>One file per map, beside the terrain itself, and deliberately readable - the whole point is
 * that a human can look at it and see which island owns what.
 */
public final class FarTerrainClaims {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Overlapping positions kept in the file. Enough to see the shape of it, not the whole island. */
    private static final int MAX_RECORDED_OVERLAPS = 256;

    /** Log lines per session, so a badly overlapping pair does not fill the log. */
    private static final int MAX_LOG_LINES = 12;

    /** Persisted shape. Chunk positions are packed x/z longs - compact, and still plain numbers. */
    private static final class Data {
        /** island name to the chunk positions it has been seen capturing. */
        Map<String, Set<Long>> islands = new LinkedHashMap<>();
        /** "IslandA|IslandB" to the positions both have claimed. */
        Map<String, List<int[]>> overlaps = new LinkedHashMap<>();
    }

    private final String map;
    private final Path file;
    private Data data;
    private boolean dirty;
    private int logged;

    /** Reverse index: position to the island that claimed it, built once on load. */
    private final Map<Long, String> owner = new HashMap<>();

    public FarTerrainClaims(String map) {
        this.map = map;
        this.file = SBSFiles.root().resolve("render")
                .resolve(FarTerrainStore.slug(map) + "_islands.json");
    }

    private synchronized Data data() {
        if (data == null) {
            data = read();
            for (Map.Entry<String, Set<Long>> entry : data.islands.entrySet()) {
                for (Long key : entry.getValue()) {
                    owner.putIfAbsent(key, entry.getKey());
                }
            }
        }
        return data;
    }

    private Data read() {
        try {
            if (Files.isRegularFile(file)) {
                Data loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                        new TypeToken<Data>() { }.getType());
                if (loaded != null) {
                    if (loaded.islands == null) {
                        loaded.islands = new LinkedHashMap<>();
                    }
                    if (loaded.overlaps == null) {
                        loaded.overlaps = new LinkedHashMap<>();
                    }
                    return loaded;
                }
            }
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.warn(
                    "[SBS][Terrain] could not read the island claims for {} ({}) - starting fresh",
                    map, e.toString());
        }
        return new Data();
    }

    /**
     * Writes pending changes. Called on the IO worker alongside the terrain save, and only there, so
     * two saves never overlap.
     *
     * <p>Only the copy is taken under the lock; serializing and writing happen outside it. The client
     * thread calls {@link #claim} for every captured chunk, and holding the lock across the write made
     * each chunk packet that arrived during a save wait for the disk.
     */
    public void save() {
        Data snapshot;
        synchronized (this) {
            if (!dirty || data == null) {
                return;
            }
            dirty = false;
            snapshot = copy(data);
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(snapshot), StandardCharsets.UTF_8);
        } catch (Exception e) {
            synchronized (this) {
                dirty = true;
            }
            SkyblockSimplifiedSBS.LOGGER.warn(
                    "[SBS][Terrain] could not write the island claims for {} ({})", map, e.toString());
        }
    }

    /**
     * A copy of {@code source} that later claims cannot change. The position arrays are never written
     * after they are added, so they are shared rather than copied.
     */
    private static Data copy(Data source) {
        Data out = new Data();
        for (Map.Entry<String, Set<Long>> entry : source.islands.entrySet()) {
            out.islands.put(entry.getKey(), new HashSet<>(entry.getValue()));
        }
        for (Map.Entry<String, List<int[]>> entry : source.overlaps.entrySet()) {
            out.overlaps.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return out;
    }

    /** The island-to-claimed-keys map of a legacy claims file, for the Main Map split migration. */
    static Map<String, Set<Long>> readLegacyIslands(String mapSlug) {
        Path file = SBSFiles.root().resolve("render").resolve(mapSlug + "_islands.json");
        try {
            if (Files.isRegularFile(file)) {
                Data data = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                        new TypeToken<Data>() { }.getType());
                if (data != null && data.islands != null) {
                    return data.islands;
                }
            }
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.warn(
                    "[SBS][Terrain] could not read the legacy claims {} ({})", file, e.toString());
        }
        return Map.of();
    }

    /**
     * Forgets every claim and deletes the file - the record has to go whenever the terrain it
     * explains does, or the next capture is measured against positions that no longer exist.
     */
    public synchronized void deleteFile() {
        data = new Data();
        owner.clear();
        dirty = false;
        logged = 0;
        try {
            Files.deleteIfExists(file);
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.warn(
                    "[SBS][Terrain] could not delete the island claims for {} ({})", map, e.toString());
        }
    }

    /**
     * Records that {@code island} captured the chunk at {@code x,z}, and reports it when another
     * island already had that position.
     *
     * <p>The log line carries the coordinates on purpose: an overlap is only actionable once you can
     * see <i>where</i> the two islands sit on top of each other and how far apart they would have to
     * be moved.
     */
    public synchronized void claim(String island, int x, int z) {
        if (island == null || island.isBlank()) {
            return;
        }
        long key = FarTerrainStore.key(x, z);
        String previous = owner.get(key);
        if (island.equals(previous)) {
            return;   // already recorded, nothing changed
        }
        if (previous != null) {
            recordOverlap(previous, island, x, z);
        }
        owner.put(key, island);
        data().islands.computeIfAbsent(island, k -> new HashSet<>()).add(key);
        dirty = true;
    }

    private void recordOverlap(String first, String second, int x, int z) {
        String pair = first.compareTo(second) <= 0 ? first + " | " + second : second + " | " + first;
        List<int[]> positions = data().overlaps.computeIfAbsent(pair, k -> new ArrayList<>());
        if (positions.size() < MAX_RECORDED_OVERLAPS) {
            positions.add(new int[]{x, z});
        }
        if (logged < MAX_LOG_LINES) {
            logged++;
            SkyblockSimplifiedSBS.LOGGER.warn(
                    "[SBS][Terrain] OVERLAP on the '{}' map: chunk {},{} (blocks {},{} to {},{}) is "
                            + "claimed by BOTH '{}' and '{}' - {} such positions recorded so far",
                    map, x, z, x * 16, z * 16, x * 16 + 15, z * 16 + 15, first, second,
                    positions.size());
        }
        // Said once, plainly, and with the answer in it. The whole reason this record exists is that
        // an overlap has no visible symptom beyond the wrong island showing up in the distance - so
        // the log has to name the island to move rather than leave that as an exercise.
        if (positions.size() == SPLIT_ADVICE_AT) {
            String smaller = data().islands.getOrDefault(first, Set.of()).size()
                    <= data().islands.getOrDefault(second, Set.of()).size() ? first : second;
            SkyblockSimplifiedSBS.LOGGER.warn(
                    "[SBS][Terrain] '{}' and '{}' now collide in {} places on the '{}' map and are "
                            + "overwriting each other. '{}' should be moved to SEPARATE_MAPS, and "
                            + "this map's terrain deleted once afterwards.",
                    first, second, positions.size(), map, smaller);
        }
    }

    /** Overlapping positions after which the "one of these needs its own map" advice is printed. */
    private static final int SPLIT_ADVICE_AT = 8;

    /** {@code [minX, minZ, maxX, maxZ]} in chunks for an island, or {@code null} when unseen. */
    /** The island that claimed a chunk position, or {@code null} when none has been seen to. */
    public synchronized String ownerOf(int x, int z) {
        data();   // builds the reverse index on first use
        return owner.get(FarTerrainStore.key(x, z));
    }

    public synchronized int[] boundsOf(String island) {
        Set<Long> keys = data().islands.get(island);
        if (keys == null || keys.isEmpty()) {
            return null;
        }
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (long key : keys) {
            int x = FarTerrainStore.keyX(key);
            int z = FarTerrainStore.keyZ(key);
            minX = Math.min(minX, x);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxZ = Math.max(maxZ, z);
        }
        return new int[]{minX, minZ, maxX, maxZ};
    }

    /** Every island seen on this map, most-claimed first - the anchor candidate is first. */
    public synchronized List<String> islandsBySize() {
        List<String> names = new ArrayList<>(data().islands.keySet());
        names.sort((a, b) -> Integer.compare(
                data().islands.getOrDefault(b, Set.of()).size(),
                data().islands.getOrDefault(a, Set.of()).size()));
        return names;
    }

    /** Every position an island has claimed, for the foreign-chunk purge. */
    public synchronized long[] keysOf(String island) {
        Set<Long> keys = data().islands.get(island);
        if (keys == null) {
            return new long[0];
        }
        long[] out = new long[keys.size()];
        int i = 0;
        for (long key : keys) {
            out[i++] = key;
        }
        return out;
    }

    /** Forgets an island entirely - its claims and its overlaps. Used after its chunks are purged. */
    public synchronized void removeIsland(String island) {
        Set<Long> keys = data().islands.remove(island);
        if (keys != null) {
            for (long key : keys) {
                owner.remove(key, island);
            }
            dirty = true;
        }
        data().overlaps.keySet().removeIf(pair -> {
            int bar = pair.indexOf(" | ");
            return bar >= 0 && (pair.substring(0, bar).equals(island)
                    || pair.substring(bar + 3).equals(island));
        });
    }

    /** How many positions two islands both claim, {@code 0} when they are disjoint. */
    public synchronized int overlapCount(String first, String second) {
        String pair = first.compareTo(second) <= 0 ? first + " | " + second : second + " | " + first;
        List<int[]> positions = data().overlaps.get(pair);
        return positions == null ? 0 : positions.size();
    }

    /** The settings/report line: what has been seen, and whether anything actually collides. */
    public synchronized String report() {
        StringBuilder out = new StringBuilder();
        for (String island : islandsBySize()) {
            int[] box = boundsOf(island);
            int count = data().islands.getOrDefault(island, Set.of()).size();
            out.append(island).append(": ").append(count).append(" chunks");
            if (box != null) {
                out.append(" x ").append(box[0]).append("..").append(box[2])
                        .append(", z ").append(box[1]).append("..").append(box[3]);
            }
            out.append('\n');
        }
        if (data().overlaps.isEmpty()) {
            out.append("no overlaps recorded - these islands are in disjoint space");
        } else {
            for (Map.Entry<String, List<int[]>> entry : data().overlaps.entrySet()) {
                out.append("OVERLAP ").append(entry.getKey()).append(": ")
                        .append(entry.getValue().size()).append(" positions\n");
            }
        }
        return out.toString();
    }

    /** Dumps the report to the log, for the {@code /sbs terrain claims} style check. */
    public void logReport() {
        for (String line : report().split("\n")) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Terrain] {}", line);
        }
    }
}
