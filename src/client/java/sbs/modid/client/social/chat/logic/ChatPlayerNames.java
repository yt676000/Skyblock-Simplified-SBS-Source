/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.chat.logic;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Style;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Works out which player a clicked piece of chat refers to.
 *
 * <p><b>Why this reads the style rather than parsing the line.</b> Chat lines are a minefield to
 * parse – rank tags, guild and party prefixes, emblems, and messages that mention several names.
 * But the server already tells us: every player name in chat carries a {@link Style} with an
 * <i>insertion</i> (the text vanilla pastes when you shift-click a name) and usually a
 * {@code /msg &lt;name&gt;} click event. Reading that gives the exact name that was clicked, even in a
 * line naming three people, and it costs no guesswork.
 *
 * <p>Non-name text has neither, so it simply resolves to {@code null} and the click falls through.
 */
public final class ChatPlayerNames {

    /** A Minecraft username: 1-16 word characters. Guards against pasting arbitrary text as a name. */
    private static final Pattern USERNAME = Pattern.compile("\\w{1,16}");

    /** Commands whose first argument is a player name. */
    private static final String[] NAME_COMMANDS = {
            "msg", "tell", "w", "whisper", "socialoptions", "profile", "pv", "ah", "visit"};

    private ChatPlayerNames() {
    }

    /**
     * The player named by a clicked chat style, or {@code null} when it does not name one.
     *
     * <p>The insertion is tried first: it is precisely "the player's name" by definition, since it is
     * what vanilla would paste into the chat box.
     */
    public static String playerFrom(Style style) {
        if (style == null) {
            return null;
        }
        String insertion = clean(style.getInsertion());
        if (insertion != null) {
            return insertion;
        }
        return fromClickEvent(style.getClickEvent());
    }

    /** Pulls the name out of a {@code /msg <name> ...}-style click event. */
    private static String fromClickEvent(ClickEvent event) {
        String command = commandOf(event);
        if (command == null) {
            return null;
        }
        String trimmed = command.trim();
        if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        String[] parts = trimmed.split("\\s+");
        if (parts.length < 2) {
            return null;
        }
        String verb = parts[0].toLowerCase(Locale.ROOT);
        for (String candidate : NAME_COMMANDS) {
            if (candidate.equals(verb)) {
                return clean(parts[1]);
            }
        }
        return null;
    }

    /** The command text of a click event, for the two kinds that carry one. */
    private static String commandOf(ClickEvent event) {
        if (event instanceof ClickEvent.SuggestCommand suggest) {
            return suggest.command();
        }
        if (event instanceof ClickEvent.RunCommand run) {
            return run.command();
        }
        return null;
    }

    /** Accepts only something actually shaped like a username. */
    private static String clean(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return USERNAME.matcher(trimmed).matches() ? trimmed : null;
    }
}
