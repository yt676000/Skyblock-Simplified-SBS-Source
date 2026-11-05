/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.floorseven.logic;

import sbs.modid.client.dungeons.floorseven.model.RelicColour;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One relic pickup announced in chat, and the parser for it.
 *
 * <p>The line, CONFIRMED in game:
 * <pre>
 * Steve picked the Corrupted Red Relic!
 * </pre>
 * It is matched over colour-stripped text. The parser strips {@code §} codes itself as well, so it
 * gives the same answer whether it is handed the raw line or the one {@code ChatPatternRegistry}
 * already stripped. An optional {@code [RANK]} in front of the name is tolerated; the name itself is
 * a Minecraft username (1-16 of {@code A-Z a-z 0-9 _}).
 *
 * @param player the name as printed
 * @param colour which relic
 * @param self   whether {@code player} is the local player (case-insensitive name match)
 */
public record RelicPickup(String player, RelicColour colour, boolean self) {

    private static final Pattern FORMATTING = Pattern.compile("§.");

    /** The pattern {@code ChatPatternRegistry} dispatches on; group 1 the name, group 2 the colour. */
    public static final String REGEX = "^\\s*(?:\\[[^\\]]+\\]\\s*)?([A-Za-z0-9_]{1,16}) picked the Corrupted "
            + "(Red|Orange|Green|Blue|Purple) Relic!\\s*$";

    private static final Pattern LINE = Pattern.compile(REGEX);

    /** Parses a chat line, or returns {@code null} when it is not a relic pickup. */
    public static RelicPickup parse(String line, String selfName) {
        if (line == null || line.indexOf("Relic") < 0) {
            return null;
        }
        Matcher matcher = LINE.matcher(FORMATTING.matcher(line).replaceAll(""));
        return matcher.matches() ? from(matcher, selfName) : null;
    }

    /** Builds the pickup from a match of {@link #REGEX}. */
    public static RelicPickup from(Matcher matcher, String selfName) {
        RelicColour colour = RelicColour.byWord(matcher.group(2));
        if (colour == null) {
            return null;
        }
        String player = matcher.group(1);
        boolean self = selfName != null && player.equalsIgnoreCase(selfName);
        return new RelicPickup(player, colour, self);
    }
}
