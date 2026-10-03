/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.chat.model;

import sbs.modid.client.core.util.PlainText;

import java.util.regex.Pattern;

/**
 * Which channel one chat line arrived on — the sorting key behind the chat tabs.
 *
 * <p>Every line lands in exactly one channel, decided from the colour-stripped text alone: Hypixel
 * marks its channels with a prefix ({@code Party > }, {@code Guild > }, {@code From }) and nothing
 * else about a message says where it came from. Anything that is not somebody talking is
 * {@link #SYSTEM} — the server telling you something — and that is a deliberate catch-all rather
 * than a guess: an unrecognised line is sorted conservatively instead of being filed somewhere it
 * can be missed.
 *
 * <p>Nothing here touches Minecraft, on purpose. The classification is the part worth testing, and a
 * test for it should not need a client to run ({@code ChatChannelTest}).
 */
public enum ChatChannel {

    /** Public chat: {@code [MVP+] Bob: text}, NPC dialogue, anything said in the open lobby. */
    PUBLIC,

    /** {@code Party > [MVP+] Bob: text}, and the party's own join / leave / disband lines. */
    PARTY,

    /** {@code Guild > Bob [Tag]: text}, and the guild's own join / leave lines. */
    GUILD,

    /** {@code Officer > Bob [Tag]: text} — guild staff chat, which the Guild tab also shows. */
    OFFICER,

    /** {@code Co-op > Bob: text}. */
    COOP,

    /** Direct messages, both ways: {@code From [MVP+] Bob: text} and {@code To Bob: text}. */
    PRIVATE,

    /**
     * This mod's own cross-server channel: {@code [SBS] [IRC] Bob: text}. Tested before {@link #MOD}
     * — an IRC line is an {@code [SBS]} line too, and it is the more specific of the two.
     */
    IRC,

    /** The server talking: rewards, warnings, level-ups — everything with no sender. */
    SYSTEM,

    /** This mod's own {@code [SBS]} lines, kept apart so a filter cannot swallow our own alerts. */
    MOD;

    /**
     * What this mod itself may have put in front of a line before it is displayed: Better Chat's
     * timestamp and its mention marker. Matched as part of every pattern below, so our own cosmetics
     * cannot move a message to a different tab.
     */
    private static final String PRE = "^(?:\\[\\d{1,2}:\\d{2}(?:am|pm)?\\] )?(?:> )?";

    /**
     * One short glyph in front of the sender — the SBS Players badge, which is inserted before an SBS
     * user's name and therefore sits at the very start of a line they said in the open. Optional and
     * inside the pattern rather than stripped up front, so the regex can give it back when it turns
     * out to have been the first word of the message instead.
     */
    private static final String BADGE = "(?:\\S{1,2}\\s)?";

    /** A rank or guild tag: {@code [MVP+]}, {@code [NPC]}, {@code [Tag]}. */
    private static final String TAG = "(?:\\[[^\\]]+\\]\\s*)";

    /**
     * A Minecraft name — <b>optional</b>, because by the time a line reaches the tabs our own
     * display pipeline may have taken the name out of it: Streamer Mode's "Blank" replaces a name
     * with nothing, and a Text Editor rule can do the same. The line is still that player talking,
     * and the structure around the name — the rank tag, the {@code From }, the colon — survives, so
     * the colon stays required and the name does not. Losing this makes redacted public chat and
     * redacted DMs sort as server text, which on a channel tab means they are not shown at all.
     */
    private static final String NAME = "\\w{0,16}";

    /**
     * An IRC line, with or without the {@code [SBS]} badge in front of it — the channel's own
     * messages carry both, its status lines only the {@code [IRC]} tag.
     */
    private static final Pattern IRC_LINE = Pattern.compile(PRE + "(?:\\[SBS\\]\\s*)?\\[IRC\\]");

    private static final Pattern MOD_LINE = Pattern.compile(PRE + "\\[SBS\\]");
    private static final Pattern PARTY_LINE = Pattern.compile(PRE + "Party\\s*>");
    private static final Pattern GUILD_LINE = Pattern.compile(PRE + "Guild\\s*>");
    private static final Pattern OFFICER_LINE = Pattern.compile(PRE + "Officer\\s*>");
    private static final Pattern COOP_LINE = Pattern.compile(PRE + "Co-?op\\s*>");

    /** {@code From [MVP+] Bob: } / {@code To Bob: } — a direct message, either direction. */
    private static final Pattern DIRECT =
            Pattern.compile(PRE + "(?:From|To) " + BADGE + TAG + "*" + NAME + "\\s*:");

    /**
     * Somebody talking in the open: an optional rank, the name, an optional guild tag, then the
     * colon. Anchored at the name so a server line that merely contains a colon ("Party Leader: …")
     * cannot pass — whatever sits in front of the colon has to look like a Minecraft name.
     */
    private static final Pattern PUBLIC_LINE = Pattern.compile(
            PRE + BADGE + TAG + "*" + NAME + "(?:\\s*" + TAG + ")?\\s*:");

    /**
     * Party lines with no sender — joins, leaves, disbands, the roster. Only ever tested on a line
     * where nobody is talking, so "Bob: join the party" stays public chat. Deliberately phrase-based
     * rather than a bare "party": items and menus say the word too, and a Party Hat in a drop message
     * is not party chat.
     */
    private static final Pattern PARTY_EVENT = Pattern.compile(
            "(?i)(?:the party|your party|'s party|party was disbanded|in a party"
                    + "|party (?:leader|members|moderators))");

    /** Guild lines with no sender, tested under the same "nobody is talking" rule. */
    private static final Pattern GUILD_EVENT = Pattern.compile(
            "(?i)(?:the guild|your guild|guild (?:master|officer))");

    /**
     * The channel one chat line belongs to.
     *
     * @param raw the message exactly as it is about to be displayed, colour codes and all;
     *            {@code null} and blank both count as {@link #SYSTEM}
     */
    public static ChatChannel of(String raw) {
        String text = PlainText.strip(raw);
        if (text.isEmpty()) {
            return SYSTEM;
        }
        if (IRC_LINE.matcher(text).lookingAt()) {
            return IRC;
        }
        if (MOD_LINE.matcher(text).lookingAt()) {
            return MOD;
        }
        if (PARTY_LINE.matcher(text).lookingAt()) {
            return PARTY;
        }
        if (GUILD_LINE.matcher(text).lookingAt()) {
            return GUILD;
        }
        if (OFFICER_LINE.matcher(text).lookingAt()) {
            return OFFICER;
        }
        if (COOP_LINE.matcher(text).lookingAt()) {
            return COOP;
        }
        if (DIRECT.matcher(text).lookingAt()) {
            return PRIVATE;
        }
        if (PUBLIC_LINE.matcher(text).lookingAt()) {
            return PUBLIC;
        }
        // Nobody is talking on this line, so these wordings cannot be two players quoting them at
        // each other: "Bob joined the party." belongs with the party chat it is about.
        if (PARTY_EVENT.matcher(text).find()) {
            return PARTY;
        }
        if (GUILD_EVENT.matcher(text).find()) {
            return GUILD;
        }
        return SYSTEM;
    }
}
