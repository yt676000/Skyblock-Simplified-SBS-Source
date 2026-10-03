/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.settings;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import sbs.modid.client.core.command.CommandRegistry;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.dev.DevMode;
import sbs.modid.client.core.keybind.CommandShortcut;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Commands page: every SBS command from {@link CommandRegistry}, grouped by category, plus the
 * player's own shortcuts.
 *
 * <p><b>A click fills in the chat box and never sends.</b> It closes the menu and opens chat with the
 * command typed, so the player adds the arguments and presses Enter themselves.
 *
 * <p><b>Search is the menu's own search box</b>: it already narrows the open page's rows by label and
 * description, and each row's description carries the aliases, so name, alias and description are
 * all searched without a second text field on the page (which would have had to rebuild the rows on
 * every keystroke - the focus-loss trap in {@code ui/AGENTS.md}).
 *
 * <p><b>Not settings.</b> Rows carry stable ids from the command ({@link CommandRegistry.Command#id()}),
 * never from their label, and the page shows no favourite stars ({@code SBSMainScreen.optionIdOf}).
 */
public final class CommandsPage {

    /** The page's module id; also its sidebar entry. */
    public static final String PAGE_ID = "commands";

    private CommandsPage() {
    }

    public static List<SettingRow> rows() {
        List<SettingRow> rows = new ArrayList<>();
        rows.add(SettingRow.label("Click a command to type it into chat - nothing is sent until you press Enter"));
        rows.add(SettingRow.label("§8The search box above finds commands by name, alias or description"));

        Map<String, List<CommandRegistry.Command>> byCategory = new LinkedHashMap<>();
        List<CommandRegistry.Command> developer = new ArrayList<>();
        for (CommandRegistry.Command c : CommandRegistry.visible(DevMode.ACTIVE)) {
            if (c.visibility() == CommandRegistry.Visibility.DEV_ONLY) {
                developer.add(c);
            } else {
                byCategory.computeIfAbsent(c.category(), k -> new ArrayList<>()).add(c);
            }
        }
        for (var entry : byCategory.entrySet()) {
            rows.add(SettingRow.label("— " + entry.getKey() + " —"));
            for (CommandRegistry.Command c : entry.getValue()) {
                rows.add(row(c));
            }
        }
        shortcuts(rows);
        if (!developer.isEmpty()) {
            rows.add(SettingRow.label("— Developer (developer mode only) —"));
            for (CommandRegistry.Command c : developer) {
                rows.add(row(c));
            }
        }
        return rows;
    }

    private static SettingRow row(CommandRegistry.Command c) {
        boolean on = c.available();
        String label = c.syntax() + (on ? "" : "  §8needs " + c.gateName() + " on");
        StringBuilder description = new StringBuilder(c.description());
        if (!c.aliases().isEmpty()) {
            description.append(" Also: ").append(String.join(", ", c.aliases())).append('.');
        }
        if (!c.example().isEmpty()) {
            description.append(" Example: ").append(c.example());
        }
        if (!on) {
            description.append(" Does nothing until ").append(c.gateName()).append(" is switched on.");
        }
        String typed = c.typed() + (c.args().isEmpty() ? "" : " ");
        return SettingRow.button(label, () -> prefill(typed))
                .describe(description.toString())
                .anchor(c.id())
                .disabledIf(!on);
    }

    /** The player's own alias → command shortcuts, from their config. */
    private static void shortcuts(List<SettingRow> rows) {
        List<CommandShortcut> mine = ConfigManager.getInstance().get().commandShortcuts;
        if (mine == null || mine.isEmpty()) {
            return;
        }
        rows.add(SettingRow.label("— Your shortcuts —"));
        for (CommandShortcut shortcut : mine) {
            String alias = shortcut.normalizedAlias();
            if (alias.isEmpty()) {
                continue;
            }
            String typed = "/" + alias.replaceFirst("^/", "") + " ";
            rows.add(SettingRow.button(typed.trim() + "  §8→ " + shortcut.command(), () -> prefill(typed))
                    .describe("Your shortcut: typing " + typed.trim() + " runs " + shortcut.command()
                            + ". Edit them under Command Keybinds.")
                    .anchor("cmd_shortcut_" + alias.replaceAll("[^a-z0-9]+", "_").toLowerCase(Locale.ROOT)));
        }
    }

    /** Closes the menu and opens chat with {@code text} typed in. Never sends. */
    private static void prefill(String text) {
        Minecraft.getInstance().setScreenAndShow(new ChatScreen(text, false));
    }
}
