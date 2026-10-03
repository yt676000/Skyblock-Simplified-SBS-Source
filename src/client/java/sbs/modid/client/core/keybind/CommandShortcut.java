/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.keybind;

import java.util.Locale;

/**
 * A command shortcut: a short {@link #alias} that expands to a full {@link #command} anywhere in the
 * mod's command routing (e.g. {@code garden} -> {@code /warp garden}). A tiny Gson POJO persisted
 * with the config; expansion is done by {@link CommandShortcutManager} inside
 * {@link sbs.modid.client.core.command.SBSCommands#run(String)}.
 */
public final class CommandShortcut {

    private String alias = "";
    private String command = "";

    public CommandShortcut() {
    }

    public CommandShortcut(String alias, String command) {
        this.alias = alias == null ? "" : alias;
        this.command = command == null ? "" : command;
    }

    public String alias() {
        return alias == null ? "" : alias;
    }

    /** The normalized alias for matching: trimmed, lower-case, without a leading slash. */
    public String normalizedAlias() {
        String a = alias().trim().toLowerCase(Locale.ROOT);
        return a.startsWith("/") ? a.substring(1) : a;
    }

    public void setAlias(String alias) {
        this.alias = alias == null ? "" : alias;
    }

    public String command() {
        return command == null ? "" : command;
    }

    public void setCommand(String command) {
        this.command = command == null ? "" : command;
    }

    public boolean isValid() {
        return !normalizedAlias().isEmpty() && !command().isBlank();
    }
}
