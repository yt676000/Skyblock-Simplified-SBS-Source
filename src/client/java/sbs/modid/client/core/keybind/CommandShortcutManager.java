/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.keybind;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Registry and expander for {@link CommandShortcut}s (alias → full command). The authoritative list
 * lives in the persisted {@link SBSConfig#commandShortcuts}, mirroring {@link CommandKeybindManager}.
 *
 * <p>{@link #expand(String)} is called from {@link sbs.modid.client.core.command.SBSCommands#run(String)}:
 * if the first word of an input matches an alias, it is replaced by the alias's command (keeping any
 * trailing arguments), so a shortcut works everywhere the mod routes commands – typed in chat, from
 * a keybind, or from another shortcut.
 */
public final class CommandShortcutManager {

    private static CommandShortcutManager instance;

    private CommandShortcutManager() {
    }

    public static CommandShortcutManager getInstance() {
        if (instance == null) {
            instance = new CommandShortcutManager();
        }
        return instance;
    }

    private List<CommandShortcut> shortcuts() {
        SBSConfig config = ConfigManager.getInstance().get();
        if (config.commandShortcuts == null) {
            config.commandShortcuts = new ArrayList<>();
        }
        return config.commandShortcuts;
    }

    public List<CommandShortcut> getShortcuts() {
        return Collections.unmodifiableList(shortcuts());
    }

    public CommandShortcut add() {
        CommandShortcut shortcut = new CommandShortcut();
        shortcuts().add(shortcut);
        save();
        return shortcut;
    }

    public void remove(CommandShortcut shortcut) {
        if (shortcuts().remove(shortcut)) {
            save();
        }
    }

    public void save() {
        try {
            ConfigManager.getInstance().save();
        } catch (Throwable t) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS] Failed to save command shortcuts", t);
        }
    }

    /**
     * Expands the first word of {@code input} if it matches a shortcut alias, else returns the input
     * unchanged. Both {@code garden} and {@code /garden} resolve; trailing args are preserved
     * ({@code garden foo} → {@code /warp garden foo} when the alias command is {@code /warp garden}).
     */
    public String expand(String input) {
        if (input == null || input.isBlank()) {
            return input;
        }
        String trimmed = input.trim();
        boolean hadSlash = trimmed.startsWith("/");
        String body = hadSlash ? trimmed.substring(1) : trimmed;
        String[] parts = body.split("\\s+", 2);
        String first = parts[0].toLowerCase(Locale.ROOT);
        for (CommandShortcut shortcut : shortcuts()) {
            if (shortcut.isValid() && shortcut.normalizedAlias().equals(first)) {
                String rest = parts.length > 1 ? " " + parts[1] : "";
                return shortcut.command().trim() + rest;
            }
        }
        return input;
    }
}
