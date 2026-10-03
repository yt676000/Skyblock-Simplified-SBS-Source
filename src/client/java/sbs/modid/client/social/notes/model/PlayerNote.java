/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.notes.model;

import java.util.ArrayList;
import java.util.List;

/**
 * One noted player, exactly as the notes file stores it (Gson maps the fields by name, so a field
 * name is part of the file format: never rename one).
 *
 * <p>{@link #name} is the name the player was <i>last seen</i> under, not an id - Minecraft names
 * change. {@link #uuid} is the identity once it is known, and it is only ever learned from the game
 * (a tab-list entry or a player standing in the world), never typed. See {@link PlayerNoteBook} for
 * the rename merge.
 */
public final class PlayerNote {

    /** Last seen name, 1-16 of {@code [A-Za-z0-9_]}; validated before it gets here. */
    public String name;

    /** Dashed account UUID, or {@code null} until the player has been seen in game. */
    public String uuid;

    public NoteTag tag = NoteTag.NEUTRAL;

    /** Free text, already cleaned and capped by {@link PlayerNoteBook#cleanText}. */
    public String note = "";

    /** Names this account was noted under before a rename, oldest first, capped. */
    public List<String> formerNames = new ArrayList<>();

    /**
     * {@code true} once another account has been seen using {@link #name}: this player renamed away
     * from it and has not been seen since. A stale entry is never matched by name - only by its
     * UUID - so the new owner of the name is not warned about what the old one did.
     */
    public boolean nameStale;

    /** Epoch millis. */
    public long created;
    public long updated;

    public PlayerNote() {
    }

    public PlayerNote(String name, long now) {
        this.name = name;
        this.created = now;
        this.updated = now;
    }

    /** A null tag or list read from a hand-edited file would otherwise surface as an NPE later. */
    void normalise() {
        if (tag == null) {
            tag = NoteTag.NEUTRAL;
        }
        if (note == null) {
            note = "";
        }
        if (formerNames == null) {
            formerNames = new ArrayList<>();
        }
    }
}
