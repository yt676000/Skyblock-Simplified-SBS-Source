/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.chat.model;

/**
 * One view of the chat: which {@link ChatChannel}s it shows.
 *
 * <p>Tabs are not channels. {@link #ALL} is every channel and is what the chat has always been;
 * {@link #GUILD} folds officer chat in with guild chat, because a player reading guild chat wants
 * their officers in it rather than in a seventh tab nobody looks at.
 *
 * <p>The constant names are the stored identity — the default-tab setting is written by name, so a
 * rename silently resets whatever the player picked ({@code AGENTS.md}: ids are assigned once).
 */
public enum ChatTab {

    /** Everything, in the order the server sent it — the chat with no filter at all. */
    ALL("All", "Every message, exactly as it arrives"),

    /** Public lobby chat: what everyone around you is saying. */
    PUBLIC("Public", "What everyone in the lobby is saying"),

    /** Party chat, plus the party's own join / leave lines. */
    PARTY("Party", "Party chat and who joined or left"),

    /** Guild chat, including officer chat. */
    GUILD("Guild", "Guild chat, officer chat included"),

    /** Co-op chat, for the people on your profile. */
    COOP("Co-op", "Chat with the people on your profile"),

    /** Direct messages in both directions. */
    PRIVATE("DMs", "Direct messages, sent and received"),

    /** The mod's own cross-server channel. Only offered while IRC Chat is switched on. */
    IRC("IRC", "The SBS channel, across every server");

    private final String label;
    private final String description;

    ChatTab(String label, String description) {
        this.label = label;
        this.description = description;
    }

    /** Short name drawn on the tab itself. */
    public String label() {
        return label;
    }

    /** One line saying what the tab shows, for the settings page. */
    public String description() {
        return description;
    }

    /** Whether a message on {@code channel} belongs on this tab. */
    public boolean accepts(ChatChannel channel) {
        if (channel == null) {
            return this == ALL;
        }
        return switch (this) {
            case ALL -> true;
            case PUBLIC -> channel == ChatChannel.PUBLIC;
            case PARTY -> channel == ChatChannel.PARTY;
            case GUILD -> channel == ChatChannel.GUILD || channel == ChatChannel.OFFICER;
            case COOP -> channel == ChatChannel.COOP;
            case PRIVATE -> channel == ChatChannel.PRIVATE;
            case IRC -> channel == ChatChannel.IRC;
        };
    }
}
