/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.carry.model;

import java.util.Locale;
import sbs.modid.client.combat.carry.logic.CarryCounter;

/**
 * The six SkyBlock slayer bosses, with everything the {@link CarryCounter} needs to recognise one
 * in the world and to parse a boss token typed in {@code /sbs trackcarry <boss> <player>}.
 *
 * <p><b>Recognition.</b> A slayer mini-boss carries a floating nametag whose text contains the boss
 * display name plus a live health readout ("§c☠ §fVoidgloom Seraph §a5M§c❤"). {@link #matchNametag}
 * therefore matches on the display name appearing inside the (colour-stripped) tag – robust to the
 * decoration Hypixel puts around it, which shifts between updates.
 *
 * <p><b>Token parsing.</b> {@link #fromToken} accepts the shorthand a carrier types – "eman", "rev",
 * "blaze3", "emant4" – matching any of the {@link #aliases} as a prefix and pulling the trailing
 * digits off as the tier. So {@code /sbs trackcarry EmanT4 Steve} is understood as Voidgloom Seraph
 * tier&nbsp;4 for Steve.
 */
public enum SlayerBoss {

    REVENANT("Revenant Horror", new String[] {"Atoned Horror"}, "Rev", 5,
            "rev", "revenant", "zombie", "atoned"),
    TARANTULA("Tarantula Broodfather", new String[] {"Primordial Broodfather"}, "Tara", 5,
            "tara", "tarantula", "spider", "broodfather", "primordial"),
    SVEN("Sven Packmaster", "Sven", 4, "sven", "wolf", "packmaster"),
    VOIDGLOOM("Voidgloom Seraph", "Eman", 4, "eman", "enderman", "voidgloom", "void", "seraph"),
    INFERNO("Inferno Demonlord", "Blaze", 4, "blaze", "inferno", "demonlord", "demon"),
    BLOODFIEND("Riftstalker Bloodfiend", "Vamp", 5, "vamp", "vampire", "bloodfiend", "riftstalker");

    /** Exact name as it appears inside the boss nametag (a substring match, colours stripped). */
    private final String displayName;
    /**
     * Other names the same slayer's boss goes by in the world. The top tier of a slayer can be a
     * renamed boss of its own – Revenant V is the "Atoned Horror", Tarantula V the "Primordial
     * Broodfather" – and a matcher that only knows the family name is blind to exactly those fights.
     */
    private final String[] altNames;
    /** Short label for the overlay ("Eman", "Blaze"). */
    private final String shortLabel;
    /** Highest tier this slayer offers (Rev/Tara/Vamp go to 5, the rest to 4). */
    private final int maxTier;
    /** Accepted command shorthands, longest first so "revenant" wins over "rev". */
    private final String[] aliases;

    SlayerBoss(String displayName, String shortLabel, int maxTier, String... aliases) {
        this(displayName, new String[0], shortLabel, maxTier, aliases);
    }

    SlayerBoss(String displayName, String[] altNames, String shortLabel, int maxTier,
               String... aliases) {
        this.displayName = displayName;
        this.altNames = altNames;
        this.shortLabel = shortLabel;
        this.maxTier = maxTier;
        this.aliases = aliases;
    }

    public String displayName() {
        return displayName;
    }

    public String shortLabel() {
        return shortLabel;
    }

    public int maxTier() {
        return maxTier;
    }

    /** The overlay label for this boss at a tier, e.g. {@code "Eman T4"} ({@code "Eman"} if tier 0). */
    public String label(int tier) {
        return tier > 0 ? shortLabel + " T" + tier : shortLabel;
    }

    /**
     * The boss whose display name appears in {@code strippedNametag}, or {@code null} when none does.
     * Case-insensitive so a client whose tag casing differs still matches.
     */
    public static SlayerBoss matchNametag(String strippedNametag) {
        if (strippedNametag == null || strippedNametag.isEmpty()) {
            return null;
        }
        String lower = strippedNametag.toLowerCase(Locale.ROOT);
        for (SlayerBoss boss : values()) {
            if (lower.contains(boss.displayName.toLowerCase(Locale.ROOT))) {
                return boss;
            }
            for (String alt : boss.altNames) {
                if (lower.contains(alt.toLowerCase(Locale.ROOT))) {
                    return boss;
                }
            }
        }
        return null;
    }

    /**
     * Parses a boss shorthand token ("eman", "blaze3", "EmanT4") into a boss, or {@code null} when it
     * matches no slayer. Any trailing digits are the tier and are stripped before the alias compare;
     * use {@link #tierInToken} to read that tier back out.
     */
    public static SlayerBoss fromToken(String token) {
        if (token == null) {
            return null;
        }
        String core = token.toLowerCase(Locale.ROOT).trim();
        // Strip a trailing tier ("blaze3", "emant4" -> "blaze", "emant"), and a lone trailing 't'.
        core = core.replaceAll("t?\\d+$", "");
        if (core.endsWith("t") && core.length() > 1) {
            core = core.substring(0, core.length() - 1);
        }
        if (core.isEmpty()) {
            return null;
        }
        for (SlayerBoss boss : values()) {
            for (String alias : boss.aliases) {
                if (alias.equals(core)) {
                    return boss;
                }
            }
        }
        return null;
    }

    /** The tier embedded in a boss token ("emant4" -> 4, "eman" -> 0), clamped to this boss's max. */
    public int tierInToken(String token) {
        if (token == null) {
            return 0;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)$")
                .matcher(token.toLowerCase(Locale.ROOT).trim());
        if (!m.find()) {
            return 0;
        }
        try {
            return Math.max(0, Math.min(maxTier, Integer.parseInt(m.group(1))));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
