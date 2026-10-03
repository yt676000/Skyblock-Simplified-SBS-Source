/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.fishing.logic;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Sorts chat lines about the Golden Fish into spawn / gone / unknown.
 *
 * <p><b>No line here has been seen.</b> The only expected wording is the spawn line "You spot a
 * Golden Fish surface from beneath the lava!", from the feature request, and it is unconfirmed. So
 * the match is deliberately tolerant - any system line naming "Golden Fish", sorted by verb - and
 * every such line is logged as {@code [SBS][GoldenFish]} by the caller, so one probe session shows the
 * real wording. A line naming the fish that fits neither verb set is {@link Kind#UNKNOWN}: logged,
 * and never acted on.
 */
public final class GoldenFishChat {

    public enum Kind { NONE, SPAWN, GONE, UNKNOWN }

    /**
     * A player's own message ("[MVP+] Name: saw a golden fish") - a colon-terminated name before the
     * text. Hypixel's system lines about the fish carry no such prefix, as far as is known.
     */
    private static final Pattern PLAYER_CHAT = Pattern.compile(
            "^(?:[A-Za-z-]+ > )?(?:\\[[^\\]]+\\]\\s*)*[A-Za-z0-9_]{1,16}(?: \\[[^\\]]+\\])?: ");

    private static final Pattern SPAWN = Pattern.compile("\\b(spot|spotted|surfaces?|appears?|emerges?)\\b");
    private static final Pattern GONE = Pattern.compile(
            "\\b(caught|reeled|landed|swam away|escaped|got away|disappear(?:ed|s)?)\\b");

    private GoldenFishChat() {
    }

    /** @param plain the colour-stripped line */
    public static Kind classify(String plain) {
        if (plain == null) {
            return Kind.NONE;
        }
        String lower = plain.toLowerCase(Locale.ROOT);
        if (!lower.contains("golden fish") || PLAYER_CHAT.matcher(plain).find()) {
            return Kind.NONE;
        }
        // Gone first: "The Golden Fish swam away" also has no spawn verb, but a catch line could
        // conceivably mention where it surfaced.
        if (GONE.matcher(lower).find()) {
            return Kind.GONE;
        }
        if (SPAWN.matcher(lower).find()) {
            return Kind.SPAWN;
        }
        return Kind.UNKNOWN;
    }
}
