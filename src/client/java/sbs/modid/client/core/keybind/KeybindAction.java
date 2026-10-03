/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.keybind;

import java.util.ArrayList;
import java.util.List;

/**
 * One step of a {@link CommandKeybind}: a command (or chat message), or the next line of a rotating
 * list, plus the {@link #delayMs()} to wait before it runs.
 *
 * <p>A keybind holds an ordered list of these, so one key can fire a whole sequence – e.g. warp,
 * wait 2 s, then run a command that only works once you have arrived.
 *
 * <p>Kept a plain POJO so {@link sbs.modid.client.core.config.ConfigManager}'s Gson (de)serializes it as
 * part of the config with no adapter; the rotation index is {@code transient} because it is a
 * runtime cursor, not user data.
 */
public final class KeybindAction {

    /** Action kinds (persisted as a string so the values stay readable in config.json). */
    public static final String TYPE_COMMAND = "command";  // run {@link #command} once
    public static final String TYPE_CYCLE = "cycle";      // run the next {@link #messages} line

    /** Delay bounds in milliseconds. 100 000 ms = 100 s, plenty for any warp-then-command chain. */
    public static final int DELAY_MIN = 0;
    public static final int DELAY_MAX = 100_000;

    /** {@link #TYPE_COMMAND} or {@link #TYPE_CYCLE}. */
    private String type = TYPE_COMMAND;

    /** Command (with leading {@code /}) or plain chat message ({@link #TYPE_COMMAND}). */
    private String command = "";

    /** Messages cycled through, one per press ({@link #TYPE_CYCLE}). */
    private List<String> messages = new ArrayList<>();

    /** Milliseconds to wait before this action runs. 0 = immediately, which is the default. */
    private int delayMs;

    /** Rotating cursor for {@link #TYPE_CYCLE} (not persisted). */
    private transient int cycleIndex;

    public KeybindAction() {
    }

    public KeybindAction(String type, String command, List<String> messages, int delayMs) {
        setType(type);
        setCommand(command);
        if (messages != null) {
            this.messages = new ArrayList<>(messages);
        }
        setDelayMs(delayMs);
    }

    public String type() {
        return type == null || type.isEmpty() ? TYPE_COMMAND : type;
    }

    public void setType(String type) {
        this.type = TYPE_CYCLE.equals(type) ? TYPE_CYCLE : TYPE_COMMAND;
    }

    public boolean isCycle() {
        return TYPE_CYCLE.equals(type());
    }

    public String command() {
        return command == null ? "" : command;
    }

    public void setCommand(String command) {
        this.command = command == null ? "" : command;
    }

    public List<String> messages() {
        if (messages == null) {
            messages = new ArrayList<>();
        }
        return messages;
    }

    public int delayMs() {
        return Math.max(DELAY_MIN, Math.min(DELAY_MAX, delayMs));
    }

    /** Sets the delay, clamped to {@link #DELAY_MIN}..{@link #DELAY_MAX}. Whole milliseconds only. */
    public void setDelayMs(int delayMs) {
        this.delayMs = Math.max(DELAY_MIN, Math.min(DELAY_MAX, delayMs));
    }

    /**
     * The text this action runs right now, advancing the rotation for a cycle action; {@code ""}
     * when there is nothing to run (which still makes the action a pure {@link #delayMs()} pause).
     */
    public String nextText() {
        if (!isCycle()) {
            return command();
        }
        List<String> list = messages();
        list.removeIf(s -> s == null || s.isBlank());
        if (list.isEmpty()) {
            return "";
        }
        String line = list.get(Math.floorMod(cycleIndex, list.size()));
        cycleIndex++;
        return line;
    }

    /** A short description of this action for the keybind's overview summary. */
    public String summary() {
        if (isCycle()) {
            return messages().isEmpty() ? "(cycle – empty)" : "Cycle: " + messages().get(0) + " ...";
        }
        return command().isEmpty() ? "(no command)" : command();
    }

    /** The {@code " | "}-joined message list, i.e. how the editor shows a cycle action. */
    public String messagesLine() {
        return String.join(" | ", messages());
    }

    /** Replaces the message list from a {@code " | "}-separated line, dropping blank parts. */
    public void setMessagesLine(String value) {
        messages().clear();
        if (value == null) {
            return;
        }
        for (String part : value.split("\\|")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                messages().add(trimmed);
            }
        }
    }
}
