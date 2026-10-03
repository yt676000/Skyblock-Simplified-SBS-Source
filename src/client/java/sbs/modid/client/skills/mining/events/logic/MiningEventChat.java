/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.events.logic;

import sbs.modid.client.skills.mining.events.model.MiningEvent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the mining-event lines out of chat, one line at a time.
 *
 * <p>Every pattern is a line from the play instance's logs (2024-03-26 -> 2025-12-13), CONFIRMED:
 * <pre>
 *  ? The Gone with the Wind event starts in 20 seconds!     (glyph lost by the log's code page)
 *  ▬▬▬▬ (rule)
 *                     GONE WITH THE WIND STARTED!
 *                             Passive Active Event          (or "Mining Event")
 *  ...
 *                       GONE WITH THE WIND ENDED!
 *  The sound of pickaxes clashing against the rock has attracted the attention of the POWDER GHAST!
 *  Find the Powder Ghast near the Forge Basin!
 * </pre>
 *
 * <p><b>The header alone is not enough</b>: {@code SLAYER QUEST STARTED!} has the same shape. A
 * header naming a known event is taken at once; any other name waits for the next line, and only
 * the subtitle ({@code Passive Active Event} / {@code Mining Event}) makes it an event - which is how
 * an event added after this was written still shows up, by its raw name.
 *
 * <p>Matching is anchored over colour-stripped, trimmed text, so a player quoting a line in chat
 * (prefixed by their name) never matches. A trailing {@code (N)} - what a chat-stacking mod appends
 * to a repeated line - is ignored. Not thread-safe; fed from the chat hook on the client thread.
 */
public final class MiningEventChat {

    /** What a line meant. */
    public enum Type {
        /** {@code The X event starts in N seconds!} - the only time the game names the next event. */
        ANNOUNCED,
        STARTED,
        ENDED,
        /** The Powder Ghast spawn line. */
        GHAST,
        /** The line naming where the Powder Ghast is. */
        GHAST_ZONE
    }

    /**
     * One recognised line.
     *
     * @param type    what it was
     * @param event   the event, {@link MiningEvent#UNKNOWN} for a name not in the enum; {@code null}
     *                for the two Ghast types
     * @param rawName the name exactly as written (header capitals for STARTED / ENDED)
     * @param seconds the countdown of an {@link Type#ANNOUNCED} line, else {@code -1}
     * @param zone    the zone of a {@link Type#GHAST_ZONE} line, else {@code null}
     */
    public record Signal(Type type, MiningEvent event, String rawName, int seconds, String zone) {
    }

    private static final Pattern COLOUR = Pattern.compile("§.");

    /** A chat-stacking mod's repeat counter, e.g. {@code ... STARTED! (2)}. */
    private static final Pattern STACK_SUFFIX = Pattern.compile("\\s+\\(\\d+\\)$");

    /** Up to two leading glyph characters (one code point may be a surrogate pair), then the text. */
    private static final Pattern ANNOUNCE =
            Pattern.compile("^(?:\\S{1,2}\\s+)?The (.+?) event starts in (\\d+) seconds?!$");

    private static final Pattern HEADER = Pattern.compile("^([A-Z0-9][A-Z0-9 '&-]*?) (STARTED|ENDED)!$");

    private static final Pattern SUBTITLE = Pattern.compile("^(?:Passive Active Event|Mining Event)$");

    private static final Pattern GHAST = Pattern.compile(
            "^The sound of pickaxes clashing against the rock has attracted the attention of the POWDER GHAST!$");

    private static final Pattern GHAST_ZONE = Pattern.compile("^Find the Powder Ghast near the (.+)!$");

    /** A header whose name is not known yet, waiting for the subtitle line. */
    private String pendingName;
    private Type pendingType;

    /**
     * Feeds one chat line, raw or stripped. Returns what it meant, or {@code null} for a line that
     * is not a mining-event line (or not one yet - see the class doc).
     */
    public Signal accept(String raw) {
        String text = plain(raw);
        String waitingName = pendingName;
        Type waitingType = pendingType;
        pendingName = null;
        pendingType = null;
        if (text.isEmpty()) {
            return null;
        }
        if (waitingName != null && SUBTITLE.matcher(text).matches()) {
            return new Signal(waitingType, MiningEvent.UNKNOWN, waitingName, -1, null);
        }
        Matcher announce = ANNOUNCE.matcher(text);
        if (announce.matches()) {
            String name = announce.group(1).trim();
            return new Signal(Type.ANNOUNCED, MiningEvent.byName(name), name,
                    Integer.parseInt(announce.group(2)), null);
        }
        Matcher header = HEADER.matcher(text);
        if (header.matches()) {
            String name = header.group(1).trim();
            Type type = header.group(2).equals("STARTED") ? Type.STARTED : Type.ENDED;
            MiningEvent event = MiningEvent.byName(name);
            if (event != MiningEvent.UNKNOWN) {
                return new Signal(type, event, name, -1, null);
            }
            pendingName = name;
            pendingType = type;
            return null;
        }
        if (GHAST.matcher(text).matches()) {
            return new Signal(Type.GHAST, null, "Powder Ghast", -1, null);
        }
        Matcher zone = GHAST_ZONE.matcher(text);
        if (zone.matches()) {
            return new Signal(Type.GHAST_ZONE, null, "Powder Ghast", -1, zone.group(1).trim());
        }
        return null;
    }

    /** Forgets a header waiting for its subtitle - on a world change. */
    public void reset() {
        pendingName = null;
        pendingType = null;
    }

    /** Colour codes out, whitespace and a stacking counter trimmed. Never {@code null}. */
    public static String plain(String raw) {
        if (raw == null) {
            return "";
        }
        String text = COLOUR.matcher(raw).replaceAll("").trim();
        return STACK_SUFFIX.matcher(text).replaceFirst("").trim();
    }
}
