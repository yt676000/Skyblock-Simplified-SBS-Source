/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.command;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import sbs.modid.client.core.dev.DevMode;
import sbs.modid.client.social.chat.logic.SBSChat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

/**
 * {@code /sbs help} and {@code /sbs help <command>}, from {@link CommandRegistry} - the same list the
 * Commands page and the suggestions read.
 *
 * <p>Every command in the list is clickable, and a click only <b>fills in</b> the chat box
 * ({@link ClickEvent.SuggestCommand}): the player adds arguments and sends it themselves. Nothing is
 * ever run from a click here.
 */
public final class CommandHelp {

    private static final int COMMAND = 0xFF55FFFF;
    private static final int OFF = 0xFF777777;
    private static final int TEXT = 0xFFDDDDDD;

    private CommandHelp() {
    }

    /** Handles {@code /sbs help [command]}. */
    public static void handle(String argument) {
        String arg = argument == null ? "" : argument.trim();
        if (arg.isEmpty()) {
            list();
            return;
        }
        CommandRegistry.Command command = CommandRegistry.find(arg);
        // DEV-ONLY: lists DEV_ONLY commands only while dev mode is on
        if (command == null || (command.visibility() == CommandRegistry.Visibility.DEV_ONLY && !DevMode.ACTIVE)) {
            SBSChat.send(Component.literal(" No command called \"" + arg + "\". /sbs help lists them all.")
                    .withColor(TEXT));
            return;
        }
        detail(command);
    }

    private static void list() {
        Map<String, List<CommandRegistry.Command>> byCategory = new LinkedHashMap<>();
        // DEV-ONLY: lists DEV_ONLY commands only while dev mode is on
        for (CommandRegistry.Command c : CommandRegistry.visible(DevMode.ACTIVE)) {
            byCategory.computeIfAbsent(c.category(), k -> new ArrayList<>()).add(c);
        }
        SBSChat.send(Component.literal(" Commands - click one to fill it in, /sbs help <name> for details")
                .withColor(SBSChat.PREFIX_COLOR));
        for (var entry : byCategory.entrySet()) {
            MutableComponent line = Component.literal(" " + entry.getKey() + ": ").withColor(TEXT);
            boolean first = true;
            for (CommandRegistry.Command c : entry.getValue()) {
                if (!first) {
                    line.append(Component.literal("  ").withColor(TEXT));
                }
                first = false;
                line.append(clickable(c, c.typed()));
            }
            SBSChat.send(line);
        }
    }

    private static void detail(CommandRegistry.Command c) {
        SBSChat.send(Component.literal(" ").append(clickable(c, c.syntax())));
        SBSChat.send(Component.literal("  " + c.description()).withColor(TEXT));
        if (!c.aliases().isEmpty()) {
            SBSChat.send(Component.literal("  Also: " + String.join(", ", c.aliases())).withColor(TEXT));
        }
        if (!c.example().isEmpty()) {
            SBSChat.send(Component.literal("  Example: " + c.example()).withColor(TEXT));
        }
        if (!c.available()) {
            SBSChat.send(Component.literal("  Needs " + c.gateName() + " on.").withColor(OFF));
        }
    }

    /** A command that fills the chat box on click, with its description on hover. */
    private static Component clickable(CommandRegistry.Command c, String shown) {
        String hover = c.description() + (c.available() ? "" : "\nNeeds " + c.gateName() + " on.")
                + "\nClick to fill in the chat box - nothing is sent.";
        return Component.literal(shown).withStyle(Style.EMPTY
                .withColor(c.available() ? COMMAND : OFF)
                .withClickEvent(new ClickEvent.SuggestCommand(c.typed() + (c.args().isEmpty() ? "" : " ")))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
    }
}
