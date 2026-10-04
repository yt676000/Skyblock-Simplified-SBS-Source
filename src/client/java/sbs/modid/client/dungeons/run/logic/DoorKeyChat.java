/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.run.logic;

import sbs.modid.client.skills.farming.model.FarmingText;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies the Catacombs key / door chat lines that drive {@link WitherDoorTracker}. Pure: one line
 * in, one {@link Event} out, so every real shape is covered by a unit test.
 *
 * <p>The lines, colour-stripped, as they arrive (all {@code CONFIRMED} from the maintainer's logs; a
 * trailing {@code " (N)"} is a client-side stacking counter and is tolerated by matching without an
 * end anchor):
 * <pre>
 * [MVP+] NAME has obtained Wither Key!        NAME has obtained Wither Key!
 * A Wither Key was picked up!
 * RIGHT CLICK on a WITHER door to open it. This key can only be used to open 1 door!
 * NAME opened a WITHER door!
 * [MVP+] NAME has obtained Blood Key!
 * RIGHT CLICK on the BLOOD DOOR to open it. This key can only be used to open 1 door!
 * The BLOOD DOOR has been opened!
 * </pre>
 * The key is shared by the whole party, so a pickup by anyone arms it. Every pattern is anchored at
 * the start of the line, so a player typing one of these sentences into chat (which arrives prefixed
 * with "Party &gt;" or "NAME:") does not match.
 */
public final class DoorKeyChat {

    /** What a line means for the door state. */
    public enum Kind {
        NONE,
        WITHER_KEY,
        BLOOD_KEY,
        WITHER_OPENED,
        BLOOD_OPENED
    }

    /** A classified line; {@code player} is the named player where the line has one, else {@code null}. */
    public record Event(Kind kind, String player) {
        static final Event NONE = new Event(Kind.NONE, null);
    }

    private static final Pattern OBTAINED =
            Pattern.compile("^\\s*(?:\\[[^\\]]+\\]\\s+)?(\\w{1,16}) has obtained (Wither|Blood) Key!");
    private static final Pattern PICKED_UP = Pattern.compile("^\\s*A Wither Key was picked up!");
    private static final Pattern WITHER_PROMPT = Pattern.compile("^\\s*RIGHT CLICK on a WITHER door to open it");
    private static final Pattern BLOOD_PROMPT = Pattern.compile("^\\s*RIGHT CLICK on the BLOOD DOOR to open it");
    private static final Pattern WITHER_OPENED =
            Pattern.compile("^\\s*(?:\\[[^\\]]+\\]\\s+)?(\\w{1,16}) opened a WITHER door!");
    private static final Pattern BLOOD_OPENED = Pattern.compile("^\\s*The BLOOD DOOR has been opened!");

    private DoorKeyChat() {
    }

    /** Classifies one chat line (colour codes are stripped here). */
    public static Event parse(String line) {
        if (line == null || line.isEmpty()) {
            return Event.NONE;
        }
        String text = FarmingText.strip(line);
        Matcher m = OBTAINED.matcher(text);
        if (m.find()) {
            return new Event(m.group(2).equals("Wither") ? Kind.WITHER_KEY : Kind.BLOOD_KEY, m.group(1));
        }
        if (PICKED_UP.matcher(text).find() || WITHER_PROMPT.matcher(text).find()) {
            return new Event(Kind.WITHER_KEY, null);
        }
        if (BLOOD_PROMPT.matcher(text).find()) {
            return new Event(Kind.BLOOD_KEY, null);
        }
        m = WITHER_OPENED.matcher(text);
        if (m.find()) {
            return new Event(Kind.WITHER_OPENED, m.group(1));
        }
        if (BLOOD_OPENED.matcher(text).find()) {
            return new Event(Kind.BLOOD_OPENED, null);
        }
        return Event.NONE;
    }
}
