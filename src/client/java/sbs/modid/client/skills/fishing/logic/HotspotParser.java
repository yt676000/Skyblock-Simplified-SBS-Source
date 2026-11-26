/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.fishing.logic;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The pure half of Fishing Share: reading a hotspot's floating text and writing the lines that go to
 * chat. No game types, so every rule here is under test.
 *
 * <h2>What a hotspot looks like (unverified)</h2>
 *
 * <p>Not one hotspot appears in the play instance's logs (checked 2026-11-26) and the wiki could not
 * be fetched, so the shape below is from memory of the game and is <b>unverified</b>: a nametag
 * armor stand reading {@code HOTSPOT}, with a second stand just below it naming the buff, e.g.
 * {@code +15☂ Fishing Speed} or {@code +5α Sea Creature Chance}. The buff pattern is therefore loose
 * on purpose - any {@code +<number>} followed by at most one symbol and a stat name - and the runtime
 * logs every unparsed name it finds next to a {@code HOTSPOT} label, so one visit settles it.
 */
public final class HotspotParser {

    /** {@code +15☂ Fishing Speed}, {@code +5% Treasure Chance}, {@code + 2.5 α Sea Creature Chance}. */
    private static final Pattern BUFF = Pattern.compile(
            "^\\+\\s*(\\d+(?:[.,]\\d+)?)\\s*%?\\s*[^A-Za-z0-9\\s]?\\s*([A-Za-z][A-Za-z' ]{1,40}?)\\s*$");

    /** Outgoing body cap before the {@code [SBS]} tag: well inside the 256-char packet limit. */
    public static final int MAX_BODY = 180;

    private HotspotParser() {
    }

    /** One hotspot buff: the number as written and the stat it raises. */
    public record Buff(String amount, String stat) {
        /** {@code +15 Fishing Speed}. */
        public String label() {
            return "+" + amount + " " + stat;
        }
    }

    /** Whether a plain (colour-stripped) nametag is the hotspot's own label. */
    public static boolean isHotspotLabel(String plain) {
        return plain != null && plain.trim().equalsIgnoreCase("HOTSPOT");
    }

    /** The buff a plain nametag names, or {@code null} when it is not one. */
    public static Buff parseBuff(String plain) {
        if (plain == null) {
            return null;
        }
        Matcher m = BUFF.matcher(plain.trim());
        if (!m.matches()) {
            return null;
        }
        return new Buff(m.group(1).replace(',', '.'), m.group(2).trim());
    }

    /** What the hotspot is called in the share: {@code +15 Fishing Speed Hotspot}, or a bare name. */
    public static String hotspotName(Buff buff) {
        return buff == null ? "Fishing Hotspot" : buff.label() + " Hotspot";
    }

    /**
     * The outgoing line, in the {@code x: 1, y: 2, z: 3 | what} shape chat waypoints and the rare
     * creature parser already read. The island is appended when known.
     */
    public static String shareMessage(String what, int x, int y, int z, String island) {
        String body = String.format(Locale.ROOT, "x: %d, y: %d, z: %d | %s", x, y, z,
                what == null ? "" : what.trim());
        if (island != null && !island.isBlank()) {
            body += " (" + island.trim() + ")";
        }
        return body.length() > MAX_BODY ? body.substring(0, MAX_BODY) : body;
    }

    /** A comma-separated creature list, lower-cased and trimmed, blanks dropped. */
    public static Set<String> parseCreatureList(String csv) {
        Set<String> out = new LinkedHashSet<>();
        if (csv == null) {
            return out;
        }
        for (String part : csv.split(",")) {
            String name = part.trim().toLowerCase(Locale.ROOT);
            if (!name.isEmpty()) {
                out.add(name);
            }
        }
        return out;
    }
}
