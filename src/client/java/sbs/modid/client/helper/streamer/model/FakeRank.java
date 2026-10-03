/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.streamer.model;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The rank Streamer Mode draws in front of your own name, and the one table of how each looks.
 *
 * <p><b>The table is the whole feature.</b> Every template below is the literal {@code §} text
 * Hypixel sends, so a wrong one is a one-line fix here and nowhere else. {@code {p}} is the plus
 * colour, {@code {m}} the MVP++ main colour (gold or aqua).
 *
 * <p><b>Verified</b> from the play instance's chat logs (thousands of lines): VIP, VIP+, MVP, MVP+,
 * MVP++ in both gold and aqua, and the plus colours listed in {@link #PLUS_COLOURS}. The name after a
 * bracket carries no code of its own - it inherits the bracket's closing colour - which is why the
 * name colour here is the same code the template ends with.
 *
 * <p><b>ESTIMATED</b>: YOUTUBE and ADMIN never occur in those logs. They are the standard Hypixel
 * format and are marked as such until a real line confirms them.
 *
 * <p>Stored in the config by {@link #name()}, never by ordinal - see {@link NameMode}.
 */
public enum FakeRank {

    /** Leave whatever the server sent. */
    REAL("Real", null, null),
    /** No bracket at all; the name in the non-rank grey. */
    NONE("None", "", "7"),
    VIP("VIP", "§a[VIP]", "a"),
    VIP_PLUS("VIP+", "§a[VIP§6+§a]", "a"),
    MVP("MVP", "§b[MVP]", "b"),
    MVP_PLUS("MVP+", "§b[MVP§{p}+§b]", "b"),
    MVP_PLUS_PLUS("MVP++", "§{m}[MVP§{p}++§{m}]", "{m}"),
    /** ESTIMATED - not seen in the logs. */
    YOUTUBE("YOUTUBE", "§c[§fYOUTUBE§c]", "c"),
    /** ESTIMATED - not seen in the logs. */
    ADMIN("ADMIN", "§c[ADMIN]", "c");

    /**
     * The thirteen plus colours as legacy codes, each with the name the config shows. Every one of
     * them was seen after "[MVP" in the play-instance logs.
     */
    public static final List<String[]> PLUS_COLOURS = List.of(
            new String[] {"c", "Red"}, new String[] {"6", "Gold"}, new String[] {"a", "Green"},
            new String[] {"e", "Yellow"}, new String[] {"d", "Pink"}, new String[] {"f", "White"},
            new String[] {"9", "Blue"}, new String[] {"2", "Dark Green"},
            new String[] {"4", "Dark Red"}, new String[] {"3", "Dark Aqua"},
            new String[] {"5", "Dark Purple"}, new String[] {"8", "Dark Gray"},
            new String[] {"0", "Black"});

    /**
     * A real Hypixel rank bracket and the single space after it, anchored to the end of the text
     * before a name. An explicit list rather than "anything in brackets": the SkyBlock level
     * ("[312] ") sits in the same place, and must never be mistaken for a rank.
     */
    public static final Pattern REAL_PREFIX = Pattern.compile(
            "\\[(?:VIP\\+?|MVP\\+{0,2}|YOUTUBE|ADMIN|OWNER|GM|MOD|HELPER|PIG\\+{3}|INNIT|MCP|EVENTS)\\] $");

    /** Longest {@link #REAL_PREFIX} can be, so the search looks back no further than it must. */
    public static final int PREFIX_LOOKBACK = 10;

    private final String displayName;
    private final String template;
    private final String nameColour;

    FakeRank(String displayName, String template, String nameColour) {
        this.displayName = displayName;
        this.template = template;
        this.nameColour = nameColour;
    }

    public String displayName() {
        return displayName;
    }

    /** Whether this changes anything. */
    public boolean active() {
        return this != REAL;
    }

    /** Whether the plus colour setting means anything for this rank. */
    public boolean usesPlusColour() {
        return this == MVP_PLUS || this == MVP_PLUS_PLUS;
    }

    /**
     * What goes where the real bracket and name were: the bracket, one space (none for
     * {@link #NONE}), then the name colour, ready for the name to be appended.
     *
     * @param plus legacy code of the plus colour, e.g. {@code "c"}
     * @param aqua whether MVP++ is the aqua style rather than the default gold
     */
    public String render(String plus, boolean aqua) {
        if (template == null) {
            return "";
        }
        String p = plusCode(plus);
        String m = aqua ? "b" : "6";
        String bracket = template.replace("{p}", p).replace("{m}", m);
        String colour = "§" + nameColour.replace("{m}", m);
        return bracket.isEmpty() ? colour : bracket + " " + colour;
    }

    /** {@code plus} if it is one of the thirteen, else red - Hypixel's default. */
    static String plusCode(String plus) {
        if (plus != null) {
            String lowered = plus.trim().toLowerCase(Locale.ROOT);
            for (String[] colour : PLUS_COLOURS) {
                if (colour[0].equals(lowered)) {
                    return lowered;
                }
            }
        }
        return "c";
    }

    /** The stored value as a constant, {@link #REAL} for anything unrecognised. */
    public static FakeRank parse(String stored) {
        if (stored == null || stored.isBlank()) {
            return REAL;
        }
        try {
            return valueOf(stored.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return REAL;
        }
    }
}
