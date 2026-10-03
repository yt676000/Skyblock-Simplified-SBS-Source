/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.notes.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import sbs.modid.client.social.chat.logic.SBSChat;
import sbs.modid.client.social.notes.model.NoteTag;
import sbs.modid.client.social.notes.model.PlayerNote;
import sbs.modid.client.social.notes.model.PlayerNoteBook;
import sbs.modid.client.social.notes.ui.PlayerNotesScreen;

import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@code /sbs note}, {@code /sbs avoid}, {@code /sbs trust} and {@code /sbs notes}. Everything is
 * answered in the player's own chat; nothing is sent to the server.
 *
 * <ul>
 *   <li>{@code /sbs note <name> <text>} - write or replace the note (tag unchanged, Neutral if new);
 *       {@code /sbs note <name>} alone shows it.</li>
 *   <li>{@code /sbs note remove <name>} - delete it. "remove" is itself a legal username, so the
 *       removal form is exactly two words; {@code /sbs note remove} with text after the name notes
 *       a player called {@code remove}.</li>
 *   <li>{@code /sbs avoid <name> [reason]} / {@code /sbs trust <name> [text]} - set the tag, and the
 *       text when one is given.</li>
 *   <li>{@code /sbs notes [search]} - the notes screen.</li>
 * </ul>
 */
public final class PlayerNotesCommands {

    private PlayerNotesCommands() {
    }

    /** The verbs {@code SBSCommands} routes here. */
    public static boolean owns(String verb) {
        return switch (verb.toLowerCase(Locale.ROOT)) {
            case "note", "avoid", "trust", "notes" -> true;
            default -> false;
        };
    }

    public static void handle(String verb, String args) {
        String rest = args == null ? "" : args.trim();
        switch (verb.toLowerCase(Locale.ROOT)) {
            case "notes" -> openScreen(rest);
            case "avoid" -> tag(NoteTag.AVOID, rest, "avoid");
            case "trust" -> tag(NoteTag.TRUSTED, rest, "trust");
            default -> note(rest);
        }
    }

    /** What the [NOTE] button puts in the chat box: the command, with the current text to edit. */
    public static String editCommand(PlayerNote note) {
        return "/sbs note " + note.name + " " + note.note;
    }

    public static void openScreen(String query) {
        Minecraft minecraft = Minecraft.getInstance();
        // Deferred: the chat screen that ran the command closes itself after this returns.
        minecraft.execute(() -> minecraft.setScreenAndShow(new PlayerNotesScreen(query)));
    }

    // ------------------------------------------------------------------ verbs

    private static void note(String rest) {
        String[] parts = rest.split("\\s+", 2);
        if (rest.isEmpty()) {
            say("§7Usage: §f/sbs note <name> <text>§7, §f/sbs note remove <name>§7, §f/sbs notes");
            return;
        }
        if (parts[0].equalsIgnoreCase("remove") && parts.length == 2 && !parts[1].contains(" ")) {
            remove(parts[1]);
            return;
        }
        String name = parts[0];
        if (!PlayerNoteBook.isValidName(name)) {
            invalid(name);
            return;
        }
        PlayerNotesStore store = PlayerNotesStore.getInstance();
        if (parts.length == 1) {
            PlayerNote existing = store.book().match(name, PlayerLookup.uuidOf(name));
            say(existing == null ? "§7No note about §f" + name + "§7."
                    : "§f" + existing.name + " §7- " + tagText(existing.tag)
                    + (existing.note.isEmpty() ? "" : "§7: §f" + existing.note));
            return;
        }
        PlayerNote entry = store.put(name, PlayerLookup.uuidOf(name), null, parts[1]);
        confirm(entry);
    }

    private static void tag(NoteTag tag, String rest, String verb) {
        String[] parts = rest.split("\\s+", 2);
        if (rest.isEmpty()) {
            say("§7Usage: §f/sbs " + verb + " <name>" + (tag == NoteTag.AVOID ? " [reason]" : " [note]"));
            return;
        }
        if (!PlayerNoteBook.isValidName(parts[0])) {
            invalid(parts[0]);
            return;
        }
        PlayerNote entry = PlayerNotesStore.getInstance().put(parts[0],
                PlayerLookup.uuidOf(parts[0]), tag, parts.length > 1 ? parts[1] : null);
        confirm(entry);
    }

    private static void remove(String name) {
        if (!PlayerNoteBook.isValidName(name)) {
            invalid(name);
            return;
        }
        PlayerNotesStore store = PlayerNotesStore.getInstance();
        PlayerNote entry = store.book().match(name, PlayerLookup.uuidOf(name));
        if (entry == null) {
            say("§7No note about §f" + name + "§7.");
            return;
        }
        store.remove(entry);
        say("§7Removed the note about §f" + entry.name + "§7.");
    }

    // ------------------------------------------------------------------ tab completion

    /** Noted names, for arguments that only make sense for an existing note. */
    public static Set<String> notedNames() {
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (PlayerNote entry : PlayerNotesStore.getInstance().book().all()) {
            if (!entry.nameStale) {
                names.add(entry.name);
            }
        }
        return names;
    }

    /** Noted names plus everyone the client can currently see. */
    public static Set<String> suggestableNames() {
        Set<String> names = notedNames();
        names.addAll(PlayerLookup.visibleNames());
        return names;
    }

    // ------------------------------------------------------------------ output

    private static void confirm(PlayerNote entry) {
        if (entry == null) {
            return;
        }
        say("§7Noted §f" + entry.name + " §7as " + tagText(entry.tag)
                + (entry.note.isEmpty() ? "" : "§7: §f" + entry.note)
                + (entry.uuid == null ? " §8(matched by name until they are seen)" : ""));
    }

    private static void invalid(String name) {
        // The rejected text is not echoed back verbatim: it could be anything a paste contained.
        say("§c\"" + PlayerNoteBook.cleanText(name).substring(0, Math.min(16,
                PlayerNoteBook.cleanText(name).length())) + "\" is not a Minecraft name "
                + "(1-16 letters, digits or _).");
    }

    private static String tagText(NoteTag tag) {
        return switch (tag) {
            case AVOID -> "§cAVOID";
            case TRUSTED -> "§aTRUSTED";
            case NEUTRAL -> "§7NEUTRAL";
        };
    }

    private static void say(String text) {
        SBSChat.send(Component.literal(" " + text).withColor(SBSChat.WHITE));
    }
}
