/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.fishing.logic;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sbs.modid.client.skills.fishing.model.FishingData;

/**
 * A party member's sea creature spawn share, in the plain format fishing mods send:
 * {@code Party > [MVP+] Name: --> A RAGNAROK has spawned <--}, or the double hook form
 * {@code --> DOUBLE HOOK! Two ALLIGATORs have spawned <--}. SBS's own compat share is the same line.
 *
 * <p>The creature comes in any case; it is mapped back to {@link FishingData}'s spelling so the
 * white/blacklist matches the names the rest of the mod uses. An unknown creature is kept in
 * title case rather than dropped - a new creature should still alert.
 */
public final class PartySpawnLine {

    /** A parsed share: who sent it, which creature, and whether it was a double hook. */
    public record Spawn(String sender, String creature, boolean doubleHook) {
    }

    private static final Pattern SINGLE = Pattern.compile(
            "^Party > (?:\\[[^\\]]+\\] )*([A-Za-z0-9_]{1,16})[^:]{0,4}: --> (?:an? )?(.+?) has spawned.*<--$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DOUBLE = Pattern.compile(
            "^Party > (?:\\[[^\\]]+\\] )*([A-Za-z0-9_]{1,16})[^:]{0,4}: --> DOUBLE HOOK!? Two (.+?)s have spawned.*<--$",
            Pattern.CASE_INSENSITIVE);

    private PartySpawnLine() {
    }

    /** The share in {@code plain} (colour codes already stripped), or null when it is none. */
    public static Spawn parse(String plain) {
        if (plain == null || !plain.startsWith("Party > ") || !plain.endsWith("<--")) {
            return null;
        }
        Matcher m = DOUBLE.matcher(plain);
        boolean doubleHook = m.matches();
        if (!doubleHook) {
            m = SINGLE.matcher(plain);
            if (!m.matches()) {
                return null;
            }
        }
        String creature = canonical(m.group(2).trim());
        return creature.isEmpty() ? null : new Spawn(m.group(1), creature, doubleHook);
    }

    /** "RAGNAROK" -> "Ragnarok", "LORD JAWBUS" -> "Lord Jawbus", "GORF" -> "gorF". */
    static String canonical(String shouted) {
        for (String known : FishingData.allSeaCreatures()) {
            if (known.equalsIgnoreCase(shouted)) {
                return known;
            }
        }
        StringBuilder out = new StringBuilder(shouted.length());
        for (String word : shouted.toLowerCase(Locale.ROOT).split(" +")) {
            if (word.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }
}
