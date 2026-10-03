/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.build.command;

import java.util.Locale;

/**
 * Every {@code /..} verb, what follows it, and - the part that matters - whether it may run on a
 * server.
 *
 * <p>{@link Reach} is the single table the singleplayer-only rule is read from: the command
 * dispatcher refuses a {@link Reach#SINGLEPLAYER} verb off singleplayer before parsing its
 * arguments, and the tab completion shows the same verbs. Pure data, no game types, so the rule is
 * unit-tested ({@code BuildGateTest}) rather than trusted.
 */
public enum BuildCommand {
    // Selection - read-only, everywhere
    POS1("pos1", Reach.EVERYWHERE, "", "set corner 1 at the block you look at (or your feet)"),
    POS2("pos2", Reach.EVERYWHERE, "", "set corner 2"),
    EXPAND("expand", Reach.EVERYWHERE, "<n> [dir|all]", "push a face of the selection out"),
    CONTRACT("contract", Reach.EVERYWHERE, "<n> [dir|all]", "pull a face of the selection in"),
    SHIFT("shift", Reach.EVERYWHERE, "<n> [dir]", "move the whole selection"),
    SEL("sel", Reach.EVERYWHERE, "clear", "clear the selection"),
    SIZE("size", Reach.EVERYWHERE, "", "size and block count of the selection"),

    // Clipboard and library - everywhere (paste only places in singleplayer)
    COPY("copy", Reach.EVERYWHERE, "", "copy the selection from the loaded blocks"),
    PASTE("paste", Reach.EVERYWHERE, "", "show the clipboard as a hologram to place"),
    ROTATE("rotate", Reach.EVERYWHERE, "<90|180|270>", "turn the clipboard (and hologram) clockwise"),
    FLIP("flip", Reach.EVERYWHERE, "[x|y|z]", "mirror the clipboard (and hologram)"),
    SAVE("save", Reach.EVERYWHERE, "<name>[!]", "save the clipboard to the library (! overwrites)"),
    LOAD("load", Reach.EVERYWHERE, "<name>", "put a saved build on the clipboard"),
    LIST("list", Reach.EVERYWHERE, "[search]", "list saved builds"),
    DELETE("delete", Reach.EVERYWHERE, "<name>", "move a saved build to the library's bin"),
    SHARE("share", Reach.EVERYWHERE, "[name]", "copy a share code (SBSBP:...) of the clipboard or a saved build"),
    IMPORT("import", Reach.EVERYWHERE, "[file.nbt]", "a share code from your clipboard, or a vanilla .nbt from the folder"),
    EXPORT("export", Reach.EVERYWHERE, "<name>", "write the clipboard as a vanilla structure .nbt"),
    QUICK("quick", Reach.EVERYWHERE, "", "open Quick Paste, the grid of saved builds"),
    LIBRARY("library", Reach.EVERYWHERE, "", "manage saved builds: preview, rename, duplicate, delete, share"),
    FREECAM("freecam", Reach.EVERYWHERE, "", "fly the camera to select blocks you cannot reach (servers: gated)"),
    HOLOGRAM("hologram", Reach.EVERYWHERE, "[on|off|clear]", "show, hide or drop the hologram"),
    SELECT("select", Reach.EVERYWHERE, "connected [family|any]", "select the build you look at, no corners needed"),
    MATERIALS("materials", Reach.EVERYWHERE, "[hologram|clipboard|selection]", "blocks needed, what you have, Bazaar cost"),
    GUIDE("guide", Reach.EVERYWHERE, "[on|off]", "build along layer by layer, with the next block boxed"),
    SWAP("swap", Reach.EVERYWHERE, "<from> <to>|reset", "swap materials in the hologram (oak -> spruce)"),
    CANCEL("cancel", Reach.EVERYWHERE, "", "stop placing, a pending preview, or a running edit"),
    HELP("help", Reach.EVERYWHERE, "", "this list"),

    // Singleplayer only - these change the world
    STICK("stick", Reach.SINGLEPLAYER, "", "get the Magic Stick Thingy"),
    SET("set", Reach.SINGLEPLAYER, "[block][!]", "fill the selection; no block = the one you hold (hand / offhand)"),
    REPLACE("replace", Reach.SINGLEPLAYER, "<from> <to>[!]", "swap one block for another in the selection"),
    WALLS("walls", Reach.SINGLEPLAYER, "<block>[!]", "the four sides of the selection"),
    OUTLINE("outline", Reach.SINGLEPLAYER, "<block>[!]", "all six faces of the selection"),
    HOLLOW("hollow", Reach.SINGLEPLAYER, "[!]", "empty the inside, keep the shell"),
    FILL("fill", Reach.SINGLEPLAYER, "<block>[!]", "flood the closed air pocket you look at"),
    MOVE("move", Reach.SINGLEPLAYER, "<n> [dir][!]", "move the selection and its blocks"),
    STACK("stack", Reach.SINGLEPLAYER, "<n> [dir][!]", "repeat the selection n times"),
    CUT("cut", Reach.SINGLEPLAYER, "[!]", "copy the selection, then clear it"),
    UNDO("undo", Reach.SINGLEPLAYER, "[n]", "take back the last edit(s)"),
    REDO("redo", Reach.SINGLEPLAYER, "[n]", "do them again"),
    TIMELINE("timeline", Reach.SINGLEPLAYER, "", "every edit with its time - jump to any point");

    /** Where a verb may run. */
    public enum Reach {
        /** Reads the loaded world or the library, or draws; safe on any server. */
        EVERYWHERE,
        /** Changes the world or the inventory; only against the integrated server. */
        SINGLEPLAYER
    }

    /** What an off-singleplayer refusal says, word for word. */
    public static final String SINGLEPLAYER_ONLY =
            "Only in singleplayer - on a server Build Tools copies and shows builds, it never changes blocks.";

    private final String word;
    private final Reach reach;
    private final String usage;
    private final String help;

    BuildCommand(String word, Reach reach, String usage, String help) {
        this.word = word;
        this.reach = reach;
        this.usage = usage;
        this.help = help;
    }

    public String word() {
        return word;
    }

    public Reach reach() {
        return reach;
    }

    public String usage() {
        return usage;
    }

    public String help() {
        return help;
    }

    /** The prefix every verb is typed with: {@code //set stone}. */
    public static final String PREFIX = "//";

    /** A parsed command line: the verb and everything after it. */
    public record Invocation(BuildCommand command, String args) {
    }

    /**
     * Maps what the chat hands the client as a command - the typed line without its first slash, so
     * {@code //set stone} arrives as {@code /set stone} - to a verb and its arguments; {@code null}
     * when the line is not one of ours. The old {@code /..} root ({@code .. set stone}) maps the same.
     */
    public static Invocation fromCommandLine(String commandLine) {
        if (commandLine == null) {
            return null;
        }
        String trimmed = commandLine.trim();
        String rest;
        if (trimmed.startsWith("/")) {
            rest = trimmed.substring(1);
        } else if (trimmed.equals("..") || trimmed.startsWith(".. ")) {
            rest = trimmed.substring(2).trim();
            if (rest.isEmpty()) {
                return new Invocation(HELP, "");
            }
        } else {
            return null;
        }
        String[] split = rest.split("\s+", 2);
        BuildCommand command = parse(split[0]);
        if (command == null) {
            return null;
        }
        return new Invocation(command, split.length > 1 ? split[1].trim() : "");
    }

    /** The verb a typed word names, or {@code null}. */
    public static BuildCommand parse(String word) {
        if (word == null) {
            return null;
        }
        String lower = word.trim().toLowerCase(Locale.ROOT);
        for (BuildCommand command : values()) {
            if (command.word.equals(lower)) {
                return command;
            }
        }
        return null;
    }

    /**
     * The refusal for running this verb now, or {@code null} when it may run.
     *
     * @param singleplayer whether an integrated server is running
     */
    public String refusal(boolean singleplayer) {
        return reach == Reach.SINGLEPLAYER && !singleplayer ? SINGLEPLAYER_ONLY : null;
    }
}
