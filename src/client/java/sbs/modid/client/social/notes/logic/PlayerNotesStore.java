/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.notes.logic;

import com.google.gson.reflect.TypeToken;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.social.notes.model.NoteTag;
import sbs.modid.client.social.notes.model.PlayerNote;
import sbs.modid.client.social.notes.model.PlayerNoteBook;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The Player Notes file: {@link SBSFiles#playerNotesFile()}, loaded on first use and written once a
 * change has settled.
 *
 * <p><b>Local only.</b> Nothing here, or anywhere in the feature, sends a note anywhere: no SBS
 * backend call, no IRC, no chat line the mod sends, and it is not part of config sharing (that walks
 * {@code SBSConfig} only; {@code PlayerNotesShareTest} holds it). The notes are the player's
 * opinions about real people, and that is the whole reason for the rule.
 *
 * <p><b>A corrupt file is moved aside, not overwritten.</b> Starting empty and saving over it would
 * destroy every note the player wrote by hand on the first edit afterwards; the unreadable copy is
 * kept next to it as {@code player_notes.json.corrupt-<time>} so it can be repaired.
 */
public final class PlayerNotesStore {

    private static final PlayerNotesStore INSTANCE = new PlayerNotesStore();

    private static final int SCHEMA_VERSION = 1;
    /** Write once the last change is this old: typing a note costs one write, not one per key. */
    private static final long WRITE_DELAY_MS = 750L;

    /** The file as Gson sees it. Field names are the file format. */
    private static final class Data {
        int schemaVersion = SCHEMA_VERSION;
        List<PlayerNote> notes = new ArrayList<>();
    }

    private final PlayerNoteBook book = new PlayerNoteBook();
    private boolean loaded;
    private boolean readOnly;
    private boolean dirty;
    private long dirtyAt;
    /** Bumped on every change, so a screen can tell its rows are stale without diffing them. */
    private int generation;

    private PlayerNotesStore() {
    }

    public static PlayerNotesStore getInstance() {
        return INSTANCE;
    }

    private static Path file() {
        return SBSFiles.playerNotesFile();
    }

    /** The book, loaded on first use. Mutate it only through the methods below, so it is saved. */
    public synchronized PlayerNoteBook book() {
        if (!loaded) {
            loaded = true;
            load();
            // An edit made in the last 750 ms before quitting would otherwise never be written.
            Runtime.getRuntime().addShutdownHook(new Thread(this::flush, "SBS-Notes-Flush"));
        }
        return book;
    }

    public synchronized int generation() {
        return generation;
    }

    // ------------------------------------------------------------------ nametag index

    private int indexedGeneration = -1;
    private Map<String, NoteTag> tagByUuid = Map.of();
    /** Live entries with no UUID yet, by lower-cased name - the only ones a name alone may match. */
    private Map<String, NoteTag> tagByNameOnly = Map.of();

    /**
     * The tag to mark on a player's nametag, or {@code null}. Called per player per frame, so it
     * reads an index rebuilt only when the book changed, never the book itself. Same rule as
     * {@link PlayerNoteBook#match}: the account id decides, the name only for an entry that has none.
     */
    public synchronized NoteTag markerTag(UUID uuid, String name) {
        PlayerNoteBook current = book();
        if (indexedGeneration != generation) {
            indexedGeneration = generation;
            Map<String, NoteTag> byUuid = new HashMap<>();
            Map<String, NoteTag> byName = new HashMap<>();
            for (PlayerNote entry : current.entriesForSave()) {
                if (entry.uuid != null) {
                    byUuid.put(entry.uuid.toLowerCase(Locale.ROOT), entry.tag);
                } else if (!entry.nameStale) {
                    byName.put(PlayerNoteBook.key(entry.name), entry.tag);
                }
            }
            tagByUuid = byUuid;
            tagByNameOnly = byName;
        }
        // A UUID that cannot be an account's is never marked. (Not the whole NPC test - Hypixel
        // fabricates version-4 ids too - so the nametag mixin also asks RealPlayers.)
        String id = PlayerNoteBook.accountUuid(uuid);
        if (id == null) {
            return null;
        }
        NoteTag tag = tagByUuid.get(id);
        if (tag != null) {
            return tag;
        }
        return name == null ? null : tagByNameOnly.get(PlayerNoteBook.key(name));
    }

    // ------------------------------------------------------------------ changes

    public synchronized PlayerNote put(String name, String uuid, NoteTag tag, String text) {
        PlayerNote entry = book().put(name, uuid, tag, text, System.currentTimeMillis());
        if (entry != null) {
            markDirty();
        }
        return entry;
    }

    public synchronized boolean remove(PlayerNote entry) {
        boolean removed = book().remove(entry);
        if (removed) {
            markDirty();
        }
        return removed;
    }

    /** Edits one field of an entry already in the book (the notes screen's rows). */
    public synchronized void edit(PlayerNote entry, NoteTag tag, String text) {
        if (entry == null) {
            return;
        }
        if (tag != null) {
            entry.tag = tag;
        }
        if (text != null) {
            entry.note = PlayerNoteBook.cleanText(text);
        }
        entry.updated = System.currentTimeMillis();
        markDirty();
    }

    /** The rename merge: see {@link PlayerNoteBook#observe}. */
    public synchronized void observe(String name, String uuid) {
        String before = null;
        PlayerNote known = book().byUuid(uuid);
        if (known != null) {
            before = known.name;
        }
        if (book.observe(name, uuid, System.currentTimeMillis())) {
            markDirty();
            if (before != null && !before.equalsIgnoreCase(name)) {
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Notes] a noted player now goes by a new name "
                        + "- the note followed them by account id");
            }
        }
    }

    private void markDirty() {
        dirty = true;
        dirtyAt = System.currentTimeMillis();
        generation++;
    }

    // ------------------------------------------------------------------ file

    private void load() {
        Path path = file();
        if (!Files.isRegularFile(path)) {
            return;
        }
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            Data data = SBSFiles.GSON.fromJson(json, new TypeToken<Data>() { }.getType());
            if (data == null) {
                return;
            }
            if (data.schemaVersion > SCHEMA_VERSION) {
                readOnly = true;
                SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Notes] {} is schema {} and this build writes "
                                + "{} - the notes are used but never saved, so a newer client's file "
                                + "is not overwritten.", path.getFileName(), data.schemaVersion,
                        SCHEMA_VERSION);
            }
            int dropped = book.load(data.notes);
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Notes] loaded {} player note(s){}", book.size(),
                    dropped > 0 ? ", skipped " + dropped + " unreadable" : "");
        } catch (Exception e) {
            // Expected failure mode for a hand-edited file: keep the bytes, start empty.
            Path aside = path.resolveSibling(path.getFileName() + ".corrupt-" + System.currentTimeMillis());
            try {
                Files.move(path, aside, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception moveFailed) {
                readOnly = true;   // could not preserve it, so never write over it either
            }
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Notes] could not read the notes file ({}) - kept "
                    + "it as {} and started empty", e.toString(), readOnly ? path : aside.getFileName());
        }
    }

    /** Game-tick upkeep: writes a settled change. Nearly always a single comparison. */
    public synchronized void tick() {
        if (!dirty) {
            return;
        }
        if (readOnly) {
            dirty = false;
            return;
        }
        if (System.currentTimeMillis() - dirtyAt < WRITE_DELAY_MS) {
            return;
        }
        flush();
    }

    /** Writes now if anything is pending (client shutdown). */
    public synchronized void flush() {
        if (!dirty || readOnly) {
            return;
        }
        dirty = false;
        try {
            Path path = file();
            SBSFiles.ensureParent(path);
            Data data = new Data();
            data.notes = book.entriesForSave();
            Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temp, SBSFiles.GSON.toJson(data), StandardCharsets.UTF_8);
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            dirty = true;
            dirtyAt = System.currentTimeMillis();
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Notes] could not write the notes file: {}",
                    e.toString());
        }
    }
}
