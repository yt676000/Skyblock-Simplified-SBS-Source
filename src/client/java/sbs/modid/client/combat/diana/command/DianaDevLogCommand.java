/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.diana.command;

import net.minecraft.network.chat.Component;
import sbs.modid.client.combat.diana.devlog.DianaDevLog;
import sbs.modid.client.combat.diana.logic.DianaGuard;
import sbs.modid.client.social.chat.logic.SBSChat;

import java.util.Locale;

/**
 * {@code /sbs devlog diana [status|mark <text>|rearm|export]} - Diana Log Mode's switch.
 *
 * <p>The bare form toggles, because a tester standing in the Hub wants one command to start and the
 * same one to stop. {@code devlog} takes a feature name so a second capture can sit beside this one
 * later without another top-level command; {@code diana} is the only one today.
 *
 * <p>Available to every player, not only in developer mode: the capture has to be taken by whoever
 * is in the Hub while the ritual runs, and it only ever reads.
 */
public final class DianaDevLogCommand {

    private static final String USAGE = "/sbs devlog diana [status|mark <text>|rearm|export]";

    private DianaDevLogCommand() {
    }

    /** {@code argument} is everything after {@code devlog}. */
    public static void handle(String argument) {
        try {
            dispatch(argument);
        } catch (RuntimeException | LinkageError | StackOverflowError failure) {
            DianaCommands.fenced("/sbs devlog", failure);
        }
    }

    private static void dispatch(String argument) {
        String[] words = (argument == null ? "" : argument.trim()).split("\\s+", 3);
        if (!words[0].equalsIgnoreCase("diana")) {
            say("§7Usage: §f" + USAGE);
            return;
        }
        String verb = words.length > 1 ? words[1].toLowerCase(Locale.ROOT) : "";
        switch (verb) {
            case "" -> DianaDevLog.toggle();
            case "on", "start" -> DianaDevLog.start();
            case "off", "stop" -> DianaDevLog.stop("command");
            case "status" -> DianaDevLog.statusLines().forEach(DianaDevLogCommand::say);
            case "mark" -> DianaDevLog.mark(words.length > 2 ? words[2] : "");
            case "rearm" -> rearm();
            case "export" -> DianaDevLog.exportNewest();
            default -> say("§7Usage: §f" + USAGE);
        }
    }

    /**
     * Re-enables a tracker the guard disabled, starting from a clean state (see DianaCommands#forget).
     * Does nothing to a healthy tracker: wiping its burrows and guesses would be data loss behind a
     * reply that says nothing happened.
     */
    private static void rearm() {
        if (!DianaGuard.disabled()) {
            say("§7The Diana tracker is already running - nothing to rearm.");
            return;
        }
        DianaCommands.forget();
        say("§aThe Diana tracker is running again, with every burrow, guess and chain forgotten.");
    }

    private static void say(String text) {
        SBSChat.send(Component.literal(" " + text));
    }
}
