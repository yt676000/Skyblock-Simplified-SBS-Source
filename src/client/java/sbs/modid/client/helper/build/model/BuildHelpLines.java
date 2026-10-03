/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.build.model;

import sbs.modid.client.helper.build.command.BuildCommand;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/**
 * What the Magic Stick help card says, for each situation: short lines of the commands and keys that
 * matter right now. Every command is named through {@link BuildCommand} (so a renamed verb renames it
 * here too) and every key through the key-name function the caller passes - the bound key's real
 * name, "unbound" when there is none. Singleplayer-only commands are marked greyed on a server.
 *
 * <p>Pure, so the lines per state are unit-tested and a test checks every command token against the
 * real verb table.
 */
public final class BuildHelpLines {

    private BuildHelpLines() {
    }

    /** Which situation the card describes. */
    public enum State {
        /** Stick in hand (or a server), no selection yet. */
        NO_SELECTION,
        /** Both corners set. */
        SELECTION,
        /** A Build Tools hologram is being placed. */
        HOLOGRAM
    }

    /** One line; {@code greyed} when it cannot be used here (a singleplayer-only command on a server). */
    public record Line(String text, boolean greyed) {
    }

    /** Key codes the card names. */
    public record KeyCodes(int corner1, int corner2, int quickPaste, int library, int freecam) {
    }

    /** "Numpad 4", or "unbound" for an unbound key. */
    public static String keyLabel(int code, IntFunction<String> names) {
        return code == 0 ? "unbound" : names.apply(code);
    }

    static String cmd(BuildCommand command) {
        return BuildCommand.PREFIX + command.word();
    }

    /**
     * The card's lines.
     *
     * @param sizeLabel  the selection's {@code W×H×L · N blocks}, or null
     * @param placeKeys  the fixed placing keys' names, in order: move, height, turn, flip, snap, apply, cancel
     */
    public static List<Line> lines(State state, boolean singleplayer, boolean compact, KeyCodes keys,
                                   IntFunction<String> names, String sizeLabel, String[] placeKeys, boolean anyCorner) {
        List<Line> out = new ArrayList<>();
        if (compact) {
            String line = switch (state) {
                case NO_SELECTION -> "Corners: " + (singleplayer ? "left/right-click" : keyLabel(keys.corner1(), names)
                        + " / " + keyLabel(keys.corner2(), names)) + "  •  " + cmd(BuildCommand.HELP);
                case SELECTION -> cmd(BuildCommand.COPY) + " · " + cmd(BuildCommand.SAVE) + " · " + cmd(BuildCommand.PASTE)
                        + "  •  " + cmd(BuildCommand.LIBRARY);
                case HOLOGRAM -> placeKeys[0] + " move · " + placeKeys[2] + " turn · " + placeKeys[5] + " place · "
                        + placeKeys[6] + " cancel";
            };
            out.add(new Line(line, false));
            return out;
        }
        switch (state) {
            case NO_SELECTION -> {
                if (singleplayer) {
                    out.add(new Line("Left-click: corner 1 · Right-click: corner 2", false));
                }
                out.add(new Line("Corner keys: " + keyLabel(keys.corner1(), names) + " / " + keyLabel(keys.corner2(), names)
                        + " · " + cmd(BuildCommand.POS1) + " " + cmd(BuildCommand.POS2), false));
                out.add(new Line(cmd(BuildCommand.SELECT) + " connected · Freecam: " + keyLabel(keys.freecam(), names), false));
                if (!singleplayer) {
                    out.add(new Line("Freecam on servers: your Private Island and Gardens only", false));
                }
                if (anyCorner) {
                    out.add(new Line(SelectionUx.CLEAR_HINT, false));
                }
            }
            case SELECTION -> {
                if (sizeLabel != null) {
                    out.add(new Line("Selection " + sizeLabel, false));
                }
                out.add(new Line(cmd(BuildCommand.COPY) + " · " + cmd(BuildCommand.SAVE) + " <name> · "
                        + cmd(BuildCommand.MATERIALS), false));
                out.add(new Line(cmd(BuildCommand.SET) + " (= held block) · " + cmd(BuildCommand.REPLACE) + " · "
                        + cmd(BuildCommand.UNDO), !singleplayer));
                out.add(new Line(SelectionUx.CLEAR_HINT, false));
            }
            case HOLOGRAM -> {
                out.add(new Line(placeKeys[0] + ": move (Shift x5) · " + placeKeys[1] + ": up/down", false));
                out.add(new Line(placeKeys[2] + ": turn · " + placeKeys[3] + ": flip · " + placeKeys[4] + ": plot grid", false));
                out.add(new Line(placeKeys[5] + ": " + (singleplayer ? "place" : "pin here") + " · " + placeKeys[6]
                        + ": cancel", false));
            }
        }
        out.add(new Line(cmd(BuildCommand.LIBRARY) + " (" + keyLabel(keys.library(), names) + ") · Quick Paste: "
                + keyLabel(keys.quickPaste(), names), false));
        return out;
    }
}
