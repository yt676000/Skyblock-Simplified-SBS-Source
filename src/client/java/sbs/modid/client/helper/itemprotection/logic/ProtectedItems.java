/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.itemprotection.logic;

import com.google.gson.reflect.TypeToken;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.helper.itemprotection.model.ProtectedEntry;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The protected-items file: two disjoint maps, written debounced.
 *
 * <p><b>Global, never profile-scoped.</b> An item travels between profiles through the auction
 * house and the museum, so protection that lived in one profile's folder would drop off the moment
 * the item did what the feature exists to survive. This is why it is <i>not</i> a
 * {@code ProfileScopedStore}.
 *
 * <p><b>Two maps, and they never merge.</b> {@code items} is keyed by {@code ExtraAttributes.uuid}
 * and covers exactly one physical item; {@code types} is keyed by {@code ExtraAttributes.id} and
 * covers every stack of that kind. A toggle writes to exactly one of them - whichever the stack's
 * identity selects - so an entry can never end up in both and clearing one leaves the other whole.
 * Enforcement asks both, which is a separate question: see {@link ItemProtection}.
 *
 * <p><b>Debounce.</b> Toggling sets a dirty flag and a timestamp; {@link #tick()} from the client
 * tick writes once the change has settled. Unprotecting a dozen items in the management screen
 * costs one file write, not twelve.
 *
 * <p><b>Schema guard, inverted.</b> The usual rule refuses a file newer than the build can read,
 * because parsing a newer schema yields silently missing fields. Here the payload is nothing but
 * strings and refusing it would silently unprotect everything - the exact outcome this feature
 * exists to prevent - so a newer file is read and then never written back, which protects the
 * newer client's data from being flattened by this one.
 */
public final class ProtectedItems {

    private static final ProtectedItems INSTANCE = new ProtectedItems();

    /** Bumped only when the on-disk shape changes in a way an older build would misread. */
    private static final int SCHEMA_VERSION = 1;

    /** How long a change has to settle before it is written. */
    private static final long WRITE_DELAY_MS = 750L;

    /** What is persisted. Package-private fields: Gson writes them, nothing else does. */
    private static final class Data {
        int schemaVersion = SCHEMA_VERSION;
        /** {@code ExtraAttributes.uuid} -> entry. One physical item each. */
        Map<String, ProtectedEntry> items = new LinkedHashMap<>();
        /** {@code ExtraAttributes.id} -> entry. Every stack of that kind. */
        Map<String, ProtectedEntry> types = new LinkedHashMap<>();
    }

    private Data data;
    private boolean dirty;
    private long dirtyAt;

    /** Set when the file on disk is newer than this build understands; suppresses every write. */
    private boolean readOnly;

    /** Bumped on every change so per-frame code can cache without going stale. */
    private volatile int generation;

    private ProtectedItems() {
    }

    public static ProtectedItems getInstance() {
        return INSTANCE;
    }

    private static Path file() {
        return SBSFiles.protectedItemsFile();
    }

    private synchronized Data data() {
        if (data == null) {
            data = load();
        }
        return data;
    }

    private Data load() {
        try {
            Path path = file();
            if (Files.isRegularFile(path)) {
                String json = Files.readString(path, StandardCharsets.UTF_8);
                Data loaded = SBSFiles.GSON.fromJson(json, new TypeToken<Data>() { }.getType());
                if (loaded != null) {
                    if (loaded.items == null) {
                        loaded.items = new LinkedHashMap<>();
                    }
                    if (loaded.types == null) {
                        loaded.types = new LinkedHashMap<>();
                    }
                    if (loaded.schemaVersion > SCHEMA_VERSION) {
                        readOnly = true;
                        SkyblockSimplifiedSBS.LOGGER.warn(
                                "[SBS][Protect] {} is schema {} and this build writes {} - the list "
                                        + "is used but never saved, so a newer client's file is not "
                                        + "overwritten.",
                                path.getFileName(), loaded.schemaVersion, SCHEMA_VERSION);
                    }
                    SkyblockSimplifiedSBS.LOGGER.info(
                            "[SBS][Protect] loaded {} protected item(s), {} protected type(s)",
                            loaded.items.size(), loaded.types.size());
                    return loaded;
                }
            }
        } catch (Exception e) {
            // A corrupt store is the one expected read failure - logged at info, per the data rule.
            SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][Protect] could not read the protected-items file ({}) - starting empty",
                    e.toString());
        }
        return new Data();
    }

    // ------------------------------------------------------------------ queries

    /** Whether this {@code ExtraAttributes.uuid} names a protected item. */
    public synchronized boolean isProtectedUuid(String uuid) {
        return uuid != null && !uuid.isEmpty() && data().items.containsKey(uuid);
    }

    /** Whether this {@code ExtraAttributes.id} names a protected item type. */
    public synchronized boolean isProtectedType(String id) {
        return id != null && !id.isEmpty() && data().types.containsKey(normalizeId(id));
    }

    /** Whether anything at all is protected - the cheap gate every hot path asks first. */
    public synchronized boolean isEmpty() {
        Data current = data();
        return current.items.isEmpty() && current.types.isEmpty();
    }

    public synchronized int uniqueCount() {
        return data().items.size();
    }

    public synchronized int typeCount() {
        return data().types.size();
    }

    /** Snapshot of the unique items, newest first. */
    public synchronized List<Row> uniqueRows() {
        return rows(data().items);
    }

    /** Snapshot of the protected types, newest first. */
    public synchronized List<Row> typeRows() {
        return rows(data().types);
    }

    private static List<Row> rows(Map<String, ProtectedEntry> source) {
        List<Row> out = new ArrayList<>(source.size());
        for (Map.Entry<String, ProtectedEntry> entry : source.entrySet()) {
            out.add(new Row(entry.getKey(), entry.getValue()));
        }
        out.sort((a, b) -> Long.compare(b.entry().addedAt(), a.entry().addedAt()));
        return out;
    }

    /** One listed row: its key in whichever map it came from, plus what is known about it. */
    public record Row(String key, ProtectedEntry entry) {
    }

    /** Changes since load, for cheap invalidation in per-frame code. */
    public int generation() {
        return generation;
    }

    // ------------------------------------------------------------------ mutation

    /** Protects one physical item. Returns false when it was already protected. */
    public synchronized boolean protectUuid(String uuid, String id, String name) {
        if (uuid == null || uuid.isEmpty() || data().items.containsKey(uuid)) {
            return false;
        }
        data().items.put(uuid, new ProtectedEntry(id, name, System.currentTimeMillis()));
        markDirty();
        return true;
    }

    /** Protects an item type. Returns false when it was already protected. */
    public synchronized boolean protectType(String id, String name) {
        String key = normalizeId(id);
        if (key.isEmpty() || data().types.containsKey(key)) {
            return false;
        }
        data().types.put(key, new ProtectedEntry(key, name, System.currentTimeMillis()));
        markDirty();
        return true;
    }

    /** Drops one physical item's protection. Returns whether it was protected. */
    public synchronized boolean forgetUuid(String uuid) {
        if (uuid == null || data().items.remove(uuid) == null) {
            return false;
        }
        markDirty();
        return true;
    }

    /** Drops an item type's protection. Returns whether it was protected. */
    public synchronized boolean forgetType(String id) {
        if (data().types.remove(normalizeId(id)) == null) {
            return false;
        }
        markDirty();
        return true;
    }

    /**
     * Refreshes the remembered id / display name of an already protected item, so the management
     * screen lists an item the player is not holding under the name it has now.
     *
     * <p>Called from the decorator, which sees every protected stack that is drawn. Deliberately
     * silent: it only marks dirty when something actually changed, or a reforge would cost a file
     * write per frame.
     */
    public synchronized void seen(String uuid, String id, String name) {
        if (uuid == null || uuid.isEmpty()) {
            return;
        }
        ProtectedEntry entry = data().items.get(uuid);
        if (entry != null && entry.refresh(id, name)) {
            markDirty();
        }
    }

    /** Empties both lists. Returns how many entries were removed. */
    public synchronized int clear() {
        Data current = data();
        int removed = current.items.size() + current.types.size();
        if (removed == 0) {
            return 0;
        }
        current.items.clear();
        current.types.clear();
        markDirty();
        return removed;
    }

    private void markDirty() {
        dirty = true;
        dirtyAt = System.currentTimeMillis();
        generation++;
    }

    // ------------------------------------------------------------------ persistence

    /**
     * Writes a settled change. Called once per client tick, and does nothing on the vast majority
     * of them - which is the point of the debounce.
     */
    public synchronized void tick() {
        if (!dirty || data == null) {
            return;
        }
        if (readOnly) {
            dirty = false;   // nothing will ever write it; stop re-asking every tick
            return;
        }
        if (System.currentTimeMillis() - dirtyAt < WRITE_DELAY_MS) {
            return;
        }
        dirty = false;
        try {
            Path path = file();
            SBSFiles.ensureParent(path);
            data.schemaVersion = SCHEMA_VERSION;
            Files.writeString(path, SBSFiles.GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (Exception e) {
            dirty = true;   // keep trying rather than silently losing the list
            SkyblockSimplifiedSBS.LOGGER.warn(
                    "[SBS][Protect] could not write the protected-items file ({})", e.toString());
        }
    }

    /** Ids are compared upper-case, the way every other SkyBlock id in this mod is. */
    private static String normalizeId(String id) {
        return id == null ? "" : id.trim().toUpperCase(Locale.ROOT);
    }
}
