/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.terrain;

import sbs.modid.client.core.config.SBSFiles;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * One map's remembered terrain: every chunk the server has ever sent us there, as the raw network
 * payload it arrived in, compressed and keyed by chunk position.
 *
 * <p><b>Why raw packet bytes.</b> A chunk stored as the exact bytes the server would send needs no
 * conversion in either direction - saving is "compress what just arrived" and serving is "decode it
 * again as if it just arrived", so what gets rebuilt is bit-for-bit what Hypixel sent, block
 * entities and light included. The cost is that the format is tied to the game's network protocol,
 * which is why the file header stamps the protocol and world version: after a game update the old
 * bytes may no longer decode, so a mismatched file is discarded wholesale (it rebuilds itself as
 * the island is walked again) instead of being trusted and crashing the decoder.
 *
 * <p><b>Thread rules.</b> {@link #put}/{@link #get}/{@link #keys} are safe from any thread (the map
 * is concurrent). {@link #load}, {@link #save} and {@link #deleteFile} touch the disk and belong on
 * the module's IO worker, never on the render thread.
 *
 * <p>The file is {@code config/sbs/render/<map>.sbsr}: a header, then one
 * {@code [x][z][length][gzip bytes]} record per chunk. It is rewritten whole on save - the simple
 * format is the point, and even a large map rewrites in well under a second on the worker.
 *
 * <p>One store covers one <b>map</b> - and every island is its own map ({@link
 * FarTerrainManager#SEPARATE_MAPS}): every multi-island grouping ever tried collapsed once the
 * claim record showed the members claiming the same near-origin positions and overwriting each
 * other in the shared file.
 */
public final class FarTerrainStore {

    /** File magic: "SBSR". */
    private static final int MAGIC = 0x53425352;

    /** Bumped if the record layout ever changes. */
    private static final int FORMAT = 1;

    /**
     * The game's data version, stamped into the header: stored bytes are network-protocol shaped,
     * so a file written by another game version is untrusted wholesale rather than decoded and
     * crashed on. The constant is deprecated upstream but its replacement does not exist on this
     * compile classpath; the value is what matters and it is correct.
     */
    @SuppressWarnings("deprecation")
    private static final int GAME_VERSION = net.minecraft.SharedConstants.WORLD_VERSION;

    /**
     * Hard cap on remembered chunks per map - a runaway capture (e.g. a location detection stuck
     * wrong while travelling) must not grow a file without bound. 65536 chunks is a 4096x4096 block
     * area; the shared map holds nine islands spread across one world, so the cap is far above what
     * a single island would ever need.
     */
    private static final int MAX_CHUNKS = 65_536;

    /**
     * The cap for an {@linkplain #ephemeral session-only} map. Far lower than the persisted one:
     * these are places the mod knows nothing about (an instance, someone else's island, a lobby it
     * has no entry for), they are thrown away on the next server hop anyway, and their compressed
     * chunks sit in heap the whole time - so this is a memory ceiling, not a storage one.
     */
    private static final int EPHEMERAL_MAX_CHUNKS = 4_096;

    /**
     * Heap ceiling for a persisted map's compressed chunks. The count cap above bounds how many
     * chunks are held, not how much memory they take, and memory is what actually ends a session -
     * so this is the cap that matters. Generous enough for any real island, small enough that the
     * module can never be the reason a client runs out of heap.
     */
    private static final long MAX_BYTES = 192L * 1024L * 1024L;

    /** The same ceiling for a session-only map, which is pure heap with nothing on disk behind it. */
    private static final long EPHEMERAL_MAX_BYTES = 24L * 1024L * 1024L;

    private final String map;

    /**
     * Session-only: never read from or written to disk. Far terrain still captures, keeps and serves
     * normally within the session; the memory is simply dropped when the world goes away, so an
     * unrecognised place works while you are there without leaving a file behind forever.
     */
    private final boolean ephemeral;

    private final Path file;
    private final ConcurrentHashMap<Long, byte[]> chunks = new ConcurrentHashMap<>();

    /** Running total of the compressed bytes in {@link #chunks}, so the heap cap costs no walk. */
    private final java.util.concurrent.atomic.AtomicLong heldBytes =
            new java.util.concurrent.atomic.AtomicLong();

    private volatile boolean loaded;
    private volatile boolean dirty;

    FarTerrainStore(String map) {
        this(map, false);
    }

    FarTerrainStore(String map, boolean ephemeral) {
        this.map = map;
        this.ephemeral = ephemeral;
        this.file = SBSFiles.renderFile(slug(map));
    }

    /** Whether this map lives only for the session - see {@link #ephemeral}. */
    boolean ephemeral() {
        return ephemeral;
    }

    /** The stored (compressed) size of a chunk, or {@code 0} - the neighbour view's fidelity signal. */
    int sizeOf(long key) {
        byte[] data = chunks.get(key);
        return data == null ? 0 : data.length;
    }

    /** "Main Map" → "main_map" - the file name a map's terrain lives under. */
    static String slug(String name) {
        StringBuilder out = new StringBuilder(name.length());
        for (char c : name.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                out.append(c);
            } else if (out.length() > 0 && out.charAt(out.length() - 1) != '_') {
                out.append('_');
            }
        }
        return out.toString();
    }

    static long key(int x, int z) {
        return ((long) z << 32) | (x & 0xFFFFFFFFL);
    }

    static int keyX(long key) {
        return (int) key;
    }

    static int keyZ(long key) {
        return (int) (key >> 32);
    }

    String map() {
        return map;
    }

    /** Whether {@link #load} has completed - nothing is served before the disk has been read. */
    boolean loaded() {
        return loaded;
    }

    int size() {
        return chunks.size();
    }

    /**
     * Stores one chunk's raw packet bytes (compressed here), overwriting any older capture.
     *
     * <p>Capped by <b>bytes as well as by count</b>. A count cap alone says nothing about heap: a
     * chunk compresses to anything from a couple of kilobytes to tens of them, so 65 536 of them is
     * somewhere between a hundred megabytes and well over a gigabyte, and only the second number is
     * the one that gets a session killed. A store that quietly grew into the gigabytes is
     * indistinguishable from a leak from the outside, which is exactly how it gets reported.
     */
    void put(int x, int z, byte[] rawPacketBytes) {
        long key = key(x, z);
        byte[] compressed = compress(rawPacketBytes);
        if (!chunks.containsKey(key)
                && (chunks.size() >= chunkCap() || heldBytes.get() + compressed.length > byteCap())) {
            return;
        }
        byte[] previous = chunks.put(key, compressed);
        heldBytes.addAndGet(compressed.length - (previous == null ? 0 : previous.length));
        dirty = true;
    }

    private int chunkCap() {
        return ephemeral ? EPHEMERAL_MAX_CHUNKS : MAX_CHUNKS;
    }

    private long byteCap() {
        return ephemeral ? EPHEMERAL_MAX_BYTES : MAX_BYTES;
    }

    /** Compressed bytes currently held in heap by this store - what the status line reports. */
    long heapBytes() {
        return heldBytes.get();
    }

    /** Re-derives {@link #heldBytes} after a bulk change (a load, or a wipe). */
    private void recount() {
        long total = 0;
        for (byte[] value : chunks.values()) {
            total += value.length;
        }
        heldBytes.set(total);
    }

    /** The chunk's raw packet bytes (decompressed), or {@code null} when it was never captured. */
    byte[] get(long key) {
        byte[] compressed = chunks.get(key);
        return compressed == null ? null : decompress(compressed);
    }

    boolean has(long key) {
        return chunks.containsKey(key);
    }

    /** Drops one chunk - used when its stored bytes no longer decode. */
    void remove(long key) {
        byte[] removed = chunks.remove(key);
        if (removed != null) {
            heldBytes.addAndGet(-removed.length);
            dirty = true;
        }
    }

    /** A snapshot of every stored position, for the injection queue build. */
    long[] keys() {
        long[] out = new long[chunks.size()];
        int i = 0;
        for (Long key : chunks.keySet()) {
            if (i >= out.length) {
                break;   // a put raced the snapshot; the queue rebuilds soon anyway
            }
            out[i++] = key;
        }
        return i == out.length ? out : java.util.Arrays.copyOf(out, i);
    }

    // ------------------------------------------------------------------ disk (IO worker only)

    /** Reads the map file; a missing, corrupt or version-mismatched file starts empty. */
    void load() {
        if (ephemeral) {
            loaded = true;   // nothing on disk by design - it starts empty and stays in memory
            return;
        }
        try {
            if (Files.isRegularFile(file)) {
                byte[] all = Files.readAllBytes(file);
                try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(all))) {
                    if (in.readInt() == MAGIC && in.readInt() == FORMAT
                            && in.readInt() == GAME_VERSION) {
                        while (in.available() >= 12) {
                            int x = in.readInt();
                            int z = in.readInt();
                            byte[] data = new byte[in.readInt()];
                            in.readFully(data);
                            chunks.put(key(x, z), data);
                        }
                        sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                                "[SBS][Terrain] {}: loaded {} chunks from {}",
                                map, chunks.size(), file.getFileName());
                    } else {
                        // Another game version wrote this - forget it, it rebuilds by walking.
                        sbs.modid.SkyblockSimplifiedSBS.LOGGER.warn(
                                "[SBS][Terrain] {}: {} is from another game version - starting empty",
                                map, file.getFileName());
                    }
                }
            } else {
                sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                        "[SBS][Terrain] {}: no file yet ({}) - starting empty", map, file);
            }
        } catch (IOException | RuntimeException e) {
            chunks.clear();   // a truncated file must not leave half a map half-trusted
            sbs.modid.SkyblockSimplifiedSBS.LOGGER.warn(
                    "[SBS][Terrain] {}: could not read {} ({}) - starting empty", map, file, e.toString());
        }
        // Bulk-filled above rather than through put(), so the running byte total is derived once here.
        recount();
        loaded = true;
    }

    /**
     * One-time migration for the retired shared "Main Map" file: its chunks are routed into
     * per-island files along the claim record, and both legacy files are deleted.
     *
     * <p>Positions claimed by more than one island are <b>dropped</b>: the file only holds
     * whichever island wrote last, and the record cannot say who that was - a chunk of unknown
     * provenance in a per-island file is exactly the poison this migration removes. They rebuild
     * on the next visit. Existing per-island files are never overwritten.
     *
     * <p>Runs on the IO worker before any store load (one submission lane), so no store can open
     * the legacy file or race the new ones.
     */
    static void migrateLegacyMainMap() {
        java.nio.file.Path legacy = SBSFiles.renderFile("main_map");
        if (!Files.isRegularFile(legacy)) {
            return;
        }
        try {
            Map<String, java.util.Set<Long>> islands =
                    FarTerrainClaims.readLegacyIslands("main_map");
            // Multi-claimed positions: unknowable bytes, routed nowhere.
            Map<Long, String> route = new java.util.HashMap<>();
            java.util.Set<Long> ambiguous = new java.util.HashSet<>();
            for (Map.Entry<String, java.util.Set<Long>> entry : islands.entrySet()) {
                for (Long key : entry.getValue()) {
                    if (route.put(key, entry.getKey()) != null) {
                        ambiguous.add(key);
                    }
                }
            }
            route.keySet().removeAll(ambiguous);

            Map<String, java.util.List<long[]>> perIsland = new java.util.LinkedHashMap<>();
            Map<Long, byte[]> records = new java.util.LinkedHashMap<>();
            byte[] all = Files.readAllBytes(legacy);
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(all))) {
                if (in.readInt() != MAGIC || in.readInt() != FORMAT
                        || in.readInt() != GAME_VERSION) {
                    Files.deleteIfExists(legacy);   // another version's bytes rebuild by walking
                    return;
                }
                while (in.available() >= 12) {
                    int x = in.readInt();
                    int z = in.readInt();
                    byte[] data = new byte[in.readInt()];
                    in.readFully(data);
                    String island = route.get(key(x, z));
                    if (island != null) {
                        records.put(key(x, z), data);
                        perIsland.computeIfAbsent(island, k -> new java.util.ArrayList<>())
                                .add(new long[]{key(x, z)});
                    }
                }
            }
            for (Map.Entry<String, java.util.List<long[]>> entry : perIsland.entrySet()) {
                String island = entry.getKey();
                java.nio.file.Path target = SBSFiles.renderFile(slug(island));
                if (Files.isRegularFile(target)) {
                    continue;   // the island has its own captures already - those are newer
                }
                java.nio.file.Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
                try (DataOutputStream out = new DataOutputStream(
                        new java.io.BufferedOutputStream(Files.newOutputStream(tmp)))) {
                    out.writeInt(MAGIC);
                    out.writeInt(FORMAT);
                    out.writeInt(GAME_VERSION);
                    for (long[] key : entry.getValue()) {
                        byte[] data = records.get(key[0]);
                        out.writeInt(keyX(key[0]));
                        out.writeInt(keyZ(key[0]));
                        out.writeInt(data.length);
                        out.write(data);
                    }
                }
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                FarTerrainClaims claims = new FarTerrainClaims(island);
                for (long[] key : entry.getValue()) {
                    claims.claim(island, keyX(key[0]), keyZ(key[0]));
                }
                claims.save();
                sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                        "[SBS][Terrain] migration: {} chunks from the shared Main Map file moved "
                                + "into {}", entry.getValue().size(), target.getFileName());
            }
            Files.deleteIfExists(legacy);
            Files.deleteIfExists(SBSFiles.root().resolve("render")
                    .resolve("main_map_islands.json"));
            sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][Terrain] migration: shared Main Map file split into {} island file(s), "
                            + "{} multi-claimed chunks dropped", perIsland.size(), ambiguous.size());
        } catch (IOException | RuntimeException e) {
            sbs.modid.SkyblockSimplifiedSBS.LOGGER.warn(
                    "[SBS][Terrain] could not split the legacy Main Map file ({}) - left in place",
                    e.toString());
        }
    }

    /** Rewrites the island file if anything changed since the last save. */
    void save() {
        if (ephemeral || !dirty || !loaded) {
            return;   // ephemeral: session-only by design, never touches the disk
        }
        dirty = false;
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (DataOutputStream out = new DataOutputStream(
                    new java.io.BufferedOutputStream(Files.newOutputStream(tmp)))) {
                out.writeInt(MAGIC);
                out.writeInt(FORMAT);
                out.writeInt(GAME_VERSION);
                for (Map.Entry<Long, byte[]> entry : chunks.entrySet()) {
                    out.writeInt(keyX(entry.getKey()));
                    out.writeInt(keyZ(entry.getKey()));
                    out.writeInt(entry.getValue().length);
                    out.write(entry.getValue());
                }
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][Terrain] {}: saved {} chunks to {}", map, chunks.size(), file.getFileName());
        } catch (IOException e) {
            dirty = true;   // try again on the next save tick
            sbs.modid.SkyblockSimplifiedSBS.LOGGER.warn(
                    "[SBS][Terrain] {}: could not write {} ({})", map, file, e.toString());
        }
    }

    /** Deletes the map's file and forgets everything in memory - the settings button. */
    void deleteFile() {
        chunks.clear();
        heldBytes.set(0);
        dirty = false;
        if (ephemeral) {
            return;   // there is no file; clearing the memory above is the whole delete
        }
        try {
            Files.deleteIfExists(file);
            sbs.modid.SkyblockSimplifiedSBS.LOGGER.info("[SBS][Terrain] {}: deleted {}", map, file);
        } catch (IOException ignored) {
            // A locked file keeps its bytes; the in-memory clear already stopped serving them.
        }
    }

    // ------------------------------------------------------------------ compression

    private static byte[] compress(byte[] raw) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.max(64, raw.length / 4));
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(raw);
        } catch (IOException e) {
            return raw;   // cannot happen on in-memory streams, but never lose the chunk over it
        }
        return bytes.toByteArray();
    }

    private static byte[] decompress(byte[] compressed) {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            return gzip.readAllBytes();
        } catch (IOException e) {
            return null;   // treated as "never captured" by the caller
        }
    }
}
