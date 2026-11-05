/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.slayer.model;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The two demons the Inferno Demonlord splits into: while they are up the boss itself sits the fight
 * out, so for that stretch they are the only thing worth hitting. Each one carries a nametag of its
 * own ("☠ Quazii 2.4M❤"), which is how they are told apart from the arena's ordinary demons – a Flare
 * or Burningsoul Demon has "Demon" in its name too, so only the two proper names count.
 */
public enum InfernoDemon {

    QUAZII("Quazii"),
    TYPHOEUS("Typhoeus");

    /** Either name, matched whole, so neither turns up inside some other word. */
    private static final Pattern NAME = Pattern.compile("\\b(Quazii|Typhoeus)\\b", Pattern.CASE_INSENSITIVE);
    /** The health at the end of the tag: "2.4M❤", "850k❤", "12,000❤". */
    private static final Pattern HEALTH = Pattern.compile("([\\d.,]+[kKmMbB]?)\\s*❤");

    /** Any number as tags print them, unit suffix included: "2.4M", "12,000", "6". */
    private static final Pattern NUMBER = Pattern.compile("\\d[\\d.,]*[kKmMbB]?");

    private final String displayName;

    InfernoDemon(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** The demon a colour-stripped nametag names, or {@code null} when it names neither. */
    public static InfernoDemon inNametag(String plain) {
        if (plain == null || plain.isEmpty()) {
            return null;
        }
        Matcher matcher = NAME.matcher(plain);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1).equalsIgnoreCase("quazii") ? QUAZII : TYPHOEUS;
    }

    /**
     * The tag with every number taken out ("☠ Quazii 2.4M❤" → "☠ Quazii #❤"): the same wording with
     * different health reads the same, which is what the probe dedups on.
     */
    public static String withoutNumbers(String plain) {
        return plain == null ? "" : NUMBER.matcher(plain).replaceAll("#");
    }

    /** The health text off a demon tag ("2.4M"), or {@code null} when the tag carries none. */
    public static String healthIn(String plain) {
        if (plain == null) {
            return null;
        }
        Matcher matcher = HEALTH.matcher(plain);
        return matcher.find() ? matcher.group(1) : null;
    }
}
