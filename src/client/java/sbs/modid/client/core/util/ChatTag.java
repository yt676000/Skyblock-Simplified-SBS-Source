/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.util;

import sbs.modid.client.core.config.ConfigManager;

/**
 * The one place SBS marks a chat line it sent itself - so {@code [SBS]} in front of an outgoing
 * message is a single switch ({@code convenience.chatMessageTag}) rather than a habit each feature
 * either has or forgets.
 *
 * <p><b>This is for lines the mod puts into the server's chat</b>: a {@code !coords} answer, a Kuudra
 * call-out, a carry counter. Everyone else in the party reads them as if the player typed them, and
 * without a marker there is no way to tell "no tri" from a person from "no tri" from a client. That
 * ambiguity is the reason the tag defaults to on: a party should always be able to see which of its
 * messages a human wrote.
 *
 * <p>Messages SBS shows only to the player go through
 * {@link sbs.modid.client.social.chat.logic.SBSChat} instead, which paints its own coloured
 * {@code [SBS]} prefix and never touches the network. This switch does not affect those - it is about
 * what other players see, not about what fills your own chat.
 *
 * <p>The tag is plain text with no {@code §} codes: it goes through a chat packet the server relays,
 * and formatting codes in one are exactly what a server refuses a message over.
 *
 * <p>Callers keep their own length caps and rate limits. This adds {@value #TAG} plus a space, so a
 * cap applied <i>after</i> tagging stays correct and a cap applied before it has {@link #LENGTH}
 * characters less headroom than it thinks.
 */
public final class ChatTag {

    /** What an outgoing SBS message is marked with. */
    public static final String TAG = "[SBS]";

    /** Characters {@link #tag} adds when the switch is on - the tag plus its separating space. */
    public static final int LENGTH = TAG.length() + 1;

    private ChatTag() {
    }

    /** Whether outgoing messages are marked (defaults to yes if the config is not up yet). */
    public static boolean enabled() {
        try {
            return ConfigManager.getInstance().get().convenience.chatMessageTag;
        } catch (Exception configNotReady) {
            return true;
        }
    }

    /**
     * {@code message} as it should leave the client: {@code "[SBS] Coords: 1, 2, 3"} with the switch
     * on, unchanged with it off.
     *
     * <p>An empty message stays empty - a bare tag says nothing and would still cost a chat packet.
     * A message that already carries the tag is left alone, so a caller that tags and then passes the
     * result through another tagging path cannot produce "[SBS] [SBS] ...".
     */
    public static String tag(String message) {
        if (message == null || message.isEmpty() || !enabled() || message.startsWith(TAG)) {
            return message == null ? "" : message;
        }
        return TAG + " " + message;
    }
}
