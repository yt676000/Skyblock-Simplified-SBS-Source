/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.entity;

import java.util.regex.Pattern;

/** The SkyBlock mob name inside a floating nametag - the one implementation every tracker shares. */
public final class NametagText {

    private static final char SECTION_SIGN = (char) 0x00A7;
    private static final Pattern FORMATTING = Pattern.compile(SECTION_SIGN + ".");
    private static final Pattern BRACKET_TAG = Pattern.compile("\\[[^\\]]*\\]");
    private static final Pattern NON_NAME_CHARS = Pattern.compile("[^A-Za-z0-9'.,/\\- ]");
    private static final Pattern TRAILING_HEALTH =
            Pattern.compile("[0-9][0-9.,]*\\s*[kKmMbB]?\\s*(?:/\\s*[0-9][0-9.,]*\\s*[kKmMbB]?)?[^\\p{L}]*$");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private NametagText() {
    }

    /**
     * The SkyBlock mob name inside a nametag string, or {@code null} when none is left after the
     * decoration is stripped. Removes the colour codes, the bracketed level tag ("[Lv120]"), every
     * decoration glyph and the trailing health readout ("30/30❤", "1.2M♥", "████"), leaving just
     * the name.
     *
     * <p>The glyph strip is a <b>whitelist</b>, not a symbol list: since the July 2025 "Mob Types"
     * update every mob carries a type symbol before its name ("[Lv5] ⚔ Lapis Zombie"), and the
     * Hunting update appends a shard marker after it ("Lapis Zombie ᛤ 30/30❤"). ᛤ in particular is
     * a Unicode <i>letter</i>, so a "strip trailing symbols" rule missed it and the extracted name
     * ("⚔ Lapis Zombie ᛤ") matched nothing – which silently killed the highlight for every SkyBlock
     * mob. Real mob names are plain ASCII ("Lapis Zombie", "Barbarian Duke X", "Millennia-Aged
     * Blaze"), so everything outside ASCII letters/digits and name punctuation is decoration – that
     * also survives whatever symbol Hypixel adds next. Digits and ".,/" stay through the strip so
     * the health readout keeps its shape for the pattern that then cuts it off the tail.
     *
     * <p>Regex-heavy: per-tick callers go through {@link Tracked#mobName}, which runs it once per
     * name change.
     */
    public static String mobName(String rawName) {
        if (rawName == null) {
            return null;
        }
        String core = FORMATTING.matcher(rawName).replaceAll("");
        core = BRACKET_TAG.matcher(core).replaceAll(" ");           // [Lv120] and any other bracketed tag
        core = NON_NAME_CHARS.matcher(core).replaceAll(" ");     // type symbols, ᛤ, stars, hearts, bars
        // Trailing health ("30/30", "1,000", "12k", "1.2M") plus whatever trails it (spaces by now).
        core = TRAILING_HEALTH.matcher(core).replaceAll(" ");
        core = WHITESPACE.matcher(core).replaceAll(" ").trim();
        return core.isEmpty() ? null : core;
    }
}
