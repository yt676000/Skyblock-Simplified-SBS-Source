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
 * <p><b>Silverfish is not an eleventh puzzle; it is {@link #ICE_PATH}.</b> Until 2026-10-13 it had
 * its own entry with no solver, so its settings row could never be switched on. The maintainer's
 * 2026-10-11 log shows the tab row {@code "Ice Path: [✔]"}, the Ice Path mechanic is pushing a
 * silverfish over ice, and no {@code "Silverfish"} tab row has ever been captured, so the entry was
 * merged into Ice Path and "Silverfish" is kept only as an alias {@link #fromName} accepts. The
 * room database still must not be read as the complete puzzle set: it covers 113 rooms, not all.
 *
 * <p><b>{@link #roomKey()} is an identifier, not display text, and two of them are misspelled.</b>
 * The scanner recorded {@code "Teleport Mace"} and {@code "Tik Tak Toe"}, and those strings are what
 * {@code DungeonRoomTracker.activeRoomName()} hands back. Correcting the spelling in {@code
 * rooms.json} without correcting every consumer silently unmatches the room, and a solver that never
 * triggers looks exactly like a solver that is switched off. {@link #displayName()} is the one the
 * player is shown.
 */
public enum PuzzleType {

    CREEPER_BEAMS("Creeper", "Creeper Beams", SolverStage.PROBE_ONLY),
    THREE_WEIRDOS("Three Weirdos", "Three Weirdos", SolverStage.FINISHED),
    TIC_TAC_TOE("Tik Tak Toe", "Tic Tac Toe", SolverStage.FINISHED),
    WATER_BOARD("Waterboard", "Water Board", SolverStage.PROBE_ONLY),
    TELEPORT_MAZE("Teleport Mace", "Teleport Maze", SolverStage.PROBE_ONLY),
    HIGHER_OR_LOWER("Higher or Lower", "Higher or Lower", SolverStage.FINISHED),
    BOULDER("Boulder", "Boulder", SolverStage.FINISHED),
    ICE_FILL("Ice Fill", "Ice Fill", SolverStage.FINISHED),
    /** The silverfish puzzle. "Silverfish" was a separate entry until 2026-10-13; now an alias. */
    ICE_PATH("Ice Path", "Ice Path", SolverStage.FINISHED, "Silverfish"),
    QUIZ("Quiz", "Quiz", SolverStage.FINISHED);

    private final String roomKey;
    private final String displayName;
    private final SolverStage stage;
    private final String[] aliases;

    PuzzleType(String roomKey, String displayName, SolverStage stage, String... aliases) {
        this.roomKey = roomKey;
        this.displayName = displayName;
        this.stage = stage;
        this.aliases = aliases;
    }

    /**
     * The settings label: the display name with any alias in brackets, so a settings search for the
     * old name still finds the row - {@code "Ice Path (Silverfish)"}.
     */
    public String settingsName() {
        return aliases.length == 0 ? displayName : displayName + " (" + String.join(", ", aliases) + ")";
    }

    /**
     * How far this puzzle's solver has got. {@link #HIGHER_OR_LOWER} counts as finished because the
     * blaze solver in {@code dungeons/puzzle/logic/BlazeSolver} is what this build ships for that room -
     * the identity question in the spec is open, the solver is not.
     */
    public SolverStage stage() {
        return stage;
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
     * room database do not have to agree on spelling and neither is worth trusting to. Aliases
     * match as well, so a {@code "Silverfish"} row resolves to {@link #ICE_PATH}.
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
            for (String alias : type.aliases) {
                if (normalise(alias).equals(needle)) {
                    return type;
                }
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
