/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.chat.model;

/**
 * Where a typed message is sent — the <b>outbound</b> counterpart to {@link ChatChannel}, which only
 * ever says where a received line came from.
 *
 * <p>The two are deliberately separate types. {@link ChatTab} is a view and says so in its own first
 * line ("Tabs are not channels"); a tab can show several channels at once, and two of them
 * ({@code ALL}, {@code PRIVATE}) are not destinations at all. Deriving a send target from a view
 * type is how a message ends up somewhere nobody chose, so the destination is its own closed set and
 * {@link #from(ChatTab)} is the one place the two meet.
 *
 * <h2>Why {@link #PUBLIC} carries no command</h2>
 *
 * <p>It sends the message exactly as typed, which is what happens with this feature switched off.
 * That makes the default state provably identical to today's behaviour rather than merely intended
 * to be, and it avoids asserting that any particular "talk in public" command exists — nothing in
 * this repository has ever sent one, so claiming one would be a guess in the one place a guess is
 * least affordable.
 */
public enum SendChannel {

    /** Sent untouched. The default, and the only state that adds nothing to what the player typed. */
    PUBLIC("Public", null, 0xFFD8E4F0),

    /** {@code /pc} — party chat. */
    PARTY("Party", "pc", 0xFF57D9FF),

    /** {@code /gc} — guild chat. */
    GUILD("Guild", "gc", 0xFF5BE07A),

    /** {@code /oc} — guild officer chat. Never selected automatically; see {@link #from(ChatTab)}. */
    OFFICER("Officer", "oc", 0xFF3FBFA8),

    /** {@code /cc} — co-op chat. */
    COOP("Co-op", "cc", 0xFFFFC24D);

    private final String label;
    private final String command;
    private final int color;

    SendChannel(String label, String command, int color) {
        this.label = label;
        this.command = command;
        this.color = color;
    }

    /** Short name for the indicator and the settings row. */
    public String label() {
        return label;
    }

    /** The bare command word ({@code "pc"}), or {@code null} when messages are sent untouched. */
    public String command() {
        return command;
    }

    /** The channel's colour, used for the input border and the indicator chip. */
    public int color() {
        return color;
    }

    /** Whether this channel changes what is sent at all. */
    public boolean prefixes() {
        return command != null;
    }

    /**
     * The message as it should be sent.
     *
     * <p>The content is passed through <b>untouched</b> — nothing is inspected, trimmed, escaped or
     * rewritten. The only thing that ever happens to it is having a command put in front, and only
     * when every one of these is true:
     *
     * <ul>
     *   <li>the channel has a command at all ({@link #PUBLIC} never does),</li>
     *   <li>the message does not already start with {@code /} — <b>an explicit command always
     *       wins</b>, which is what stops a typed {@code /msg Bob hi} becoming
     *       {@code /pc /msg Bob hi} and saying it to the party instead,</li>
     *   <li>the message is not blank, since prefixing whitespace would send a bare channel command
     *       and produce whatever Hypixel does with that.</li>
     * </ul>
     */
    public String apply(String message) {
        if (command == null || message == null) {
            return message;
        }
        if (message.isBlank() || message.startsWith("/")) {
            return message;
        }
        return "/" + command + " " + message;
    }

    /**
     * The destination implied by a tab, for the tab bar's double duty as a send selector.
     *
     * <p><b>Total by construction, and conservative where a tab is not a destination.</b> Three of
     * the seven tabs do not name one, and each falls back to {@link #PUBLIC} — sending untouched —
     * rather than to a guess:
     *
     * <ul>
     *   <li>{@code ALL} is every channel at once. There is no "reply to everything".</li>
     *   <li>{@code PRIVATE} is DMs, which need a recipient. The last person who messaged you is not
     *       the same thing as the person you meant, and getting it wrong sends a private message to
     *       the wrong player — the exact failure this feature has to design against.</li>
     *   <li>{@code IRC} is the mod's own channel and does not go out as a Hypixel command; wiring it
     *       is a separate piece of work rather than a prefix.</li>
     * </ul>
     *
     * <p>{@code GUILD} maps to {@link #GUILD} and never to {@link #OFFICER}, even though the tab
     * shows both: a message meant for guild chat landing in officer chat is recoverable, and the
     * reverse is not. Officer remains reachable by choosing it directly.
     */
    public static SendChannel from(ChatTab tab) {
        if (tab == null) {
            return PUBLIC;
        }
        return switch (tab) {
            case PARTY -> PARTY;
            case GUILD -> GUILD;
            case COOP -> COOP;
            case ALL, PUBLIC, PRIVATE, IRC -> PUBLIC;
        };
    }
}
