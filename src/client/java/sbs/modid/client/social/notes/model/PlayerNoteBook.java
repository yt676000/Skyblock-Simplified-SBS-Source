/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.notes.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The notes themselves, and every rule about who a note belongs to. No Minecraft classes, so all of
 * it is unit-tested; {@code PlayerNotesStore} owns the file and the save throttle around it.
 *
 * <p><b>Names come from chat, which other players write.</b> Every name that enters the book
 * passes {@link #isValidName}, which is Minecraft's own username alphabet. Anything else is refused
 * rather than trimmed, because a name that had to be repaired is not a name, and the one that ends
 * up in a {@code /p kick} button must never carry a space or a slash.
 *
 * <p><b>Who a note belongs to.</b> The UUID when it is known, the name otherwise:
 * <ul>
 *   <li>seen with a UUID the book already has, under a new name - the same person renamed; the
 *       stored name follows them and the old one goes into {@link PlayerNote#formerNames};</li>
 *   <li>seen under a noted name with no UUID stored yet - the UUID is attached;</li>
 *   <li>seen under a noted name with a <i>different</i> UUID - somebody else now owns that name
 *       (the noted player renamed away from it). That is <b>not</b> a match: warning a stranger
 *       about what someone else did under the name is the worst mistake this feature can make. The
 *       entry is marked {@link PlayerNote#nameStale} and from then on only its UUID finds it.</li>
 *   <li>a noted account renames <i>into</i> a name another entry is filed under - that entry is
 *       merged in when it never had a UUID (it was, as far as anyone can tell, a note about this
 *       very name), and marked stale when it belongs to a different account. Either way no two live
 *       entries ever share a name, which is what {@link #load} relies on.</li>
 * </ul>
 */
public final class PlayerNoteBook {

    /** Minecraft's username alphabet and length. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    /** A note is a reminder, not an essay; long enough for "left at Maxor twice, took the chest". */
    public static final int MAX_NOTE_LENGTH = 160;

    /** How many former names one entry remembers. */
    static final int MAX_FORMER_NAMES = 5;

    private final List<PlayerNote> entries = new ArrayList<>();

    // ------------------------------------------------------------------ input rules

    public static boolean isValidName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /**
     * A note as stored: formatting codes and control characters removed, whitespace collapsed,
     * capped at {@link #MAX_NOTE_LENGTH}. The text is only ever shown back to this player, but a
     * pasted {@code §} code would still recolour the warning line it is printed in.
     */
    public static String cleanText(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(Math.min(raw.length(), MAX_NOTE_LENGTH));
        boolean space = false;
        for (int i = 0; i < raw.length() && out.length() < MAX_NOTE_LENGTH; i++) {
            char c = raw.charAt(i);
            if (c == '§') {
                i++;   // the code character after the section sign goes too
                continue;
            }
            if (Character.isWhitespace(c) || Character.isISOControl(c)) {
                space = out.length() > 0;
                continue;
            }
            if (space) {
                out.append(' ');
                space = false;
                if (out.length() >= MAX_NOTE_LENGTH) {
                    break;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    /**
     * The dashed form of a UUID that can be an account's, or {@code null}. Only version 4 can be a
     * Mojang account, so anything else is certainly not one. The converse does not hold: Hypixel also
     * fabricates version-4 UUIDs (checked 2026-09-25), so whether an entity is a real player is
     * {@code core/player/RealPlayers}' question, and callers ask it too.
     */
    public static String accountUuid(UUID uuid) {
        return uuid != null && uuid.version() == 4 ? uuid.toString() : null;
    }

    // ------------------------------------------------------------------ lookup

    /** Every entry, newest update first. A copy, safe to iterate while the book changes. */
    public List<PlayerNote> all() {
        List<PlayerNote> copy = new ArrayList<>(entries);
        copy.sort((a, b) -> Long.compare(b.updated, a.updated));
        return Collections.unmodifiableList(copy);
    }

    public int size() {
        return entries.size();
    }

    /**
     * The live entry filed under {@code name} (case-insensitive). Stale entries - their player
     * renamed away from it - are skipped; see {@link PlayerNote#nameStale}.
     */
    public PlayerNote byName(String name) {
        if (name == null) {
            return null;
        }
        for (PlayerNote entry : entries) {
            if (!entry.nameStale && entry.name.equalsIgnoreCase(name)) {
                return entry;
            }
        }
        return null;
    }

    public PlayerNote byUuid(String uuid) {
        if (uuid == null) {
            return null;
        }
        for (PlayerNote entry : entries) {
            if (uuid.equalsIgnoreCase(entry.uuid)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * The note for a player seen as {@code name} with {@code uuid} (may be {@code null} when only
     * the name is known, e.g. from a chat line). Read-only: see {@link #observe} for the merge.
     */
    public PlayerNote match(String name, String uuid) {
        PlayerNote byId = byUuid(uuid);
        if (byId != null) {
            return byId;
        }
        PlayerNote byName = byName(name);
        if (byName == null) {
            return null;
        }
        // A stored UUID that disagrees with the one in front of us is a different person.
        return uuid != null && byName.uuid != null ? null : byName;
    }

    // ------------------------------------------------------------------ changes

    /**
     * Records that the player {@code name} / {@code uuid} was seen in game, applying the rename
     * merge described on the class.
     *
     * @return {@code true} when an entry changed (the store saves), {@code false} otherwise
     */
    public boolean observe(String name, String uuid, long now) {
        if (!isValidName(name) || uuid == null) {
            return false;
        }
        PlayerNote byId = byUuid(uuid);
        if (byId != null) {
            if (byId.name.equals(name) && !byId.nameStale) {
                return false;
            }
            PlayerNote other = byName(name);
            if (other != null && other != byId) {
                if (other.uuid == null) {
                    mergeInto(byId, other);
                    entries.remove(other);
                } else {
                    other.nameStale = true;
                }
            }
            if (!byId.name.equalsIgnoreCase(name)) {
                rememberFormerName(byId, byId.name);
            }
            byId.name = name;   // also picks up a pure capitalisation change
            byId.nameStale = false;
            byId.updated = now;
            return true;
        }
        PlayerNote byName = byName(name);
        if (byName == null) {
            return false;
        }
        if (byName.uuid == null) {
            byName.uuid = uuid;
            return true;
        }
        // Another account holds the noted name now: the noted player renamed away from it.
        byName.nameStale = true;
        return true;
    }

    /**
     * Folds a name-only entry into the account entry that just took its name. The more cautious
     * tag wins, since a warning lost in a merge is the one outcome worse than a spare warning.
     */
    private static void mergeInto(PlayerNote into, PlayerNote from) {
        into.tag = severity(from.tag) > severity(into.tag) ? from.tag : into.tag;
        if (!from.note.isEmpty() && !into.note.contains(from.note)) {
            into.note = cleanText(into.note.isEmpty() ? from.note : into.note + " / " + from.note);
        }
        into.created = Math.min(into.created, from.created);
    }

    private static int severity(NoteTag tag) {
        return switch (tag) {
            case AVOID -> 2;
            case TRUSTED -> 1;
            case NEUTRAL -> 0;
        };
    }

    /**
     * Creates or edits the note for {@code name}. {@code tag} and {@code text} are each left as
     * they are when {@code null}.
     *
     * @return the entry, or {@code null} when the name is not a valid username
     */
    public PlayerNote put(String name, String uuid, NoteTag tag, String text, long now) {
        if (!isValidName(name)) {
            return null;
        }
        // What the game just told us about this name comes first: it follows a rename and retires
        // an entry whose player no longer owns the name, so the lookup below cannot create a second
        // live entry under one name.
        observe(name, uuid, now);
        PlayerNote entry = match(name, uuid);
        if (entry == null) {
            entry = new PlayerNote(name, now);
            entries.add(entry);
        }
        if (uuid != null && entry.uuid == null) {
            entry.uuid = uuid;
        }
        if (tag != null) {
            entry.tag = tag;
        }
        if (text != null) {
            entry.note = cleanText(text);
        }
        entry.updated = now;
        return entry;
    }

    public boolean remove(PlayerNote entry) {
        return entries.remove(entry);
    }

    /**
     * Replaces the whole book with {@code loaded}. Only an entry that cannot be an entry at all (no
     * valid name, or a second copy of an account already loaded) is dropped; a hand edit that put
     * two live entries under one name keeps both, the later one marked stale, so nothing a player
     * wrote is lost to a typo.
     *
     * @return how many were dropped
     */
    public int load(List<PlayerNote> loaded) {
        entries.clear();
        int dropped = 0;
        if (loaded == null) {
            return 0;
        }
        for (PlayerNote entry : loaded) {
            if (entry == null || !isValidName(entry.name)
                    || (entry.uuid != null && byUuid(entry.uuid) != null)) {
                dropped++;
                continue;
            }
            entry.normalise();
            entry.note = cleanText(entry.note);
            if (!entry.nameStale && byName(entry.name) != null) {
                entry.nameStale = true;
            }
            entries.add(entry);
        }
        return dropped;
    }

    /** The live list, for serialisation only. */
    public List<PlayerNote> entriesForSave() {
        return entries;
    }

    private static void rememberFormerName(PlayerNote entry, String former) {
        entry.formerNames.removeIf(former::equalsIgnoreCase);
        entry.formerNames.add(former);
        while (entry.formerNames.size() > MAX_FORMER_NAMES) {
            entry.formerNames.remove(0);
        }
    }

    /** Lower-cased key for per-session bookkeeping outside the book. */
    public static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
