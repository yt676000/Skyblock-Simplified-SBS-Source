/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.puzzle.model;

import java.util.Locale;

/**
 * The Catacombs puzzle rooms, each tied to the name the room database returns for it.
 *
 * <p><b>Ten of these came from the room database, and the eleventh is the reason not to trust it as
 * a complete list.</b> The first ten are exactly the rooms carrying {@code map_color: purple} in the
 * bundled {@code dungeons/rooms.json}, which the in-game dev scanner populated - a record of rooms
 * walked into rather than a wiki transcription, and it matches the official puzzle list. Bomb Defuse
 * (removed in 0.20.5) is absent and stays absent.
 *
 * <p>{@link #SILVERFISH} is absent from that database too, and <b>it still generates</b> - confirmed
 * in game by the maintainer, 2026-08-10. The database covers 113 of the Catacombs' rooms, not all of
 * them, so "not in the scan" was only ever evidence and this is what it looks like when that
 * evidence is wrong. Anything reasoning about which puzzles exist must not read the purple rooms as
 * the whole set.
 *
 * <p><b>{@link #roomKey()} is an identifier, not display text, and two of them are misspelled.</b>
 * The scanner recorded {@code "Teleport Mace"} and {@code "Tik Tak Toe"}, and those strings are what
 * {@code DungeonRoomTracker.activeRoomName()} hands back. Correcting the spelling in {@code
 * rooms.json} without correcting every consumer silently unmatches the room, and a solver that never
 * triggers looks exactly like a solver that is switched off. {@link #displayName()} is the one the
 * player is shown.
 */
public enum PuzzleType {

    CREEPER_BEAMS("Creeper", "Creeper Beams"),
    THREE_WEIRDOS("Three Weirdos", "Three Weirdos"),
    TIC_TAC_TOE("Tik Tak Toe", "Tic Tac Toe"),
    WATER_BOARD("Waterboard", "Water Board"),
    TELEPORT_MAZE("Teleport Mace", "Teleport Maze"),
    HIGHER_OR_LOWER("Higher or Lower", "Higher or Lower"),
    BOULDER("Boulder", "Boulder"),
    ICE_FILL("Ice Fill", "Ice Fill"),
    ICE_PATH("Ice Path", "Ice Path"),
    QUIZ("Quiz", "Quiz"),

    /**
     * Confirmed to still generate (2026-08-10), and the only entry here whose {@link #roomKey()} is
     * a guess: the room has never been scanned, so nothing is known to return this string and
     * {@code activeRoomName()} will not identify the room until someone records it.
     *
     * <p>Recognised from the <b>tab list</b> in the meantime, which is why it is listed at all - a
     * puzzle the tab names and this enum does not is reported as an unrecognised row, and "we cannot
     * name this puzzle" is a worse answer than we have to give. No solver exists: the mechanic has
     * not been described from a real run, and one is not being invented from the room's name.
     */
    SILVERFISH("Silverfish", "Silverfish");

    private final String roomKey;
    private final String displayName;

    PuzzleType(String roomKey, String displayName) {
        this.roomKey = roomKey;
        this.displayName = displayName;
    }

    /** The room-database key, misspellings and all. Match {@code activeRoomName()} against this. */
    public String roomKey() {
        return roomKey;
    }

    /** The official name, for anything the player reads. */
    public String displayName() {
        return displayName;
    }

    /**
     * The puzzle occupying a room, or {@code null} when the room is not a puzzle room.
     *
     * <p>Matched against both the database key and the display name, because the tab list and the
     * room database do not have to agree on spelling and neither is worth trusting to.
     */
    public static PuzzleType fromName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String needle = normalise(name);
        for (PuzzleType type : values()) {
            if (normalise(type.roomKey).equals(needle) || normalise(type.displayName).equals(needle)) {
                return type;
            }
        }
        return null;
    }

    /**
     * Lower-cased with every non-letter dropped, so {@code "Tik Tak Toe"}, {@code "Tic-Tac-Toe"} and
     * {@code "tictactoe"} all collapse to one another. Deliberately aggressive: the alternative is a
     * table of spelling variants that grows every time Hypixel or a scan disagrees by a hyphen.
     */
    private static String normalise(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (char c : text.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetter(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }
}
