/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one parser for Hypixel's "RARE DROP!" family of chat lines, shared by every tracker that books
 * them (fishing, slayer, farming) - three private copies of the same regex had drifted apart.
 *
 * <p><b>Shapes, verbatim from the play-instance logs (to 2026-09-26):</b>
 * <pre>
 * RARE DROP! Machine Gun Shortbow (+189 &lt;U+E01A&gt; Magic Find)
 * RARE DROP! (Tarantula Silk) (+241% &lt;U+E01A&gt; Magic Find)
 * VERY RARE DROP! (Null Atom) (+171% &lt;U+E01A&gt; Magic Find)
 * RARE DROP! Nether Star
 * </pre>
 * The item may or may not be in parentheses, the Magic Find may or may not carry a {@code %}, and the
 * glyph before "Magic Find" is Hypixel's private-use U+E01A (not ✯). The old per-tracker patterns kept
 * the parentheses as part of the name, so "(Null Atom)" never resolved to an item.
 *
 * <p>A line with a "name: " sender in front ("[444] ♔ [VIP] Someone: CRAZY RARE DROP! ...") is
 * another player quoting their drop - {@link #parse} anchors at the line start, so it never matches.
 */
public final class RareDropLine {

    /** How rare Hypixel says the drop is, in ascending order. */
    public enum Tier {
        RARE, VERY_RARE, CRAZY_RARE, INSANE, PRAY_TO_RNGESUS;

        /** Whether this is VERY RARE or better. */
        public boolean atLeastVeryRare() {
            return ordinal() >= VERY_RARE.ordinal();
        }
    }

    /**
     * One parsed line. {@code count} is 1 unless the line says "(2x)". {@code magicFind} is
     * {@code -1} when the line carries none.
     */
    public record Drop(Tier tier, String item, int count, int magicFind) {
    }

    private static final Pattern LINE = Pattern.compile(
            "^(RARE|VERY RARE|CRAZY RARE|INSANE|PRAY TO RNGESUS) DROP!\\s+"
                    + "(?:\\((\\d+)x\\)\\s+)?"
                    + "(\\((.+?)\\)|(.+?))"
                    + "(?:\\s+\\(\\+(\\d+)%?\\s*\\S*\\s*Magic Find\\))?\\s*$");

    private RareDropLine() {
    }

    /** The drop a colour-stripped chat line announces, or {@code null}. */
    public static Drop parse(String plain) {
        if (plain == null) {
            return null;
        }
        Matcher m = LINE.matcher(plain.trim());
        if (!m.matches()) {
            return null;
        }
        Tier tier = Tier.valueOf(m.group(1).replace(' ', '_'));
        int count = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
        String item = (m.group(4) != null ? m.group(4) : m.group(5)).trim();
        int magicFind = m.group(6) == null ? -1 : Integer.parseInt(m.group(6));
        return item.isEmpty() ? null : new Drop(tier, item, count, magicFind);
    }
}
