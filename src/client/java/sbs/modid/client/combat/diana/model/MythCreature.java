/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.diana.model;

import java.util.List;
import java.util.Locale;

/**
 * The four mythological creatures worth interrupting someone for.
 *
 * <h2>These names are hypotheses, and the enum is built around that</h2>
 *
 * <p>Not one of these four appears anywhere in this repository's data. Grepping the tree finds Minos
 * Hunter, Siamese Lynx, Minotaur, Minos Champion and Gaia Construct in
 * {@code combat/damage/model/MobCombatCatalog} - and no Minos Inquisitor, no King Minos, no Sphinx,
 * no Manticore. Under the project rule that <i>a name in a request is a hypothesis</i>, four names
 * the whole rare-creature half of this feature keys on being absent from our data is precisely the
 * case that rule exists for.
 *
 * <p>"Minos Hunter" and "Minos Champion" being present is the trap rather than the reassurance: a
 * grep that finds <i>something</i> is the easiest way to talk yourself into believing a name is
 * confirmed. A near-miss counts as absent.
 *
 * <p>So three things hold here, and all three are deliberate:
 *
 * <ul>
 *   <li>the names below are <b>defaults</b>, not literals - {@code DianaSettings} carries an
 *       override per creature, and the override is what is matched when it is set;</li>
 *   <li>matching is <b>tolerant</b> - contains, case-insensitive, after the variant prefixes and
 *       the colour codes have been stripped - because Hypixel decorates these names;</li>
 *   <li>every nametag that looks mythological but matches none of them is <b>logged</b>, so one
 *       trip in game replaces the guess without an update.</li>
 * </ul>
 *
 * <p>And the rare-creature features ship off, with the settings page saying why.
 */
public enum MythCreature {

    /** The one everybody is actually farming. */
    INQUISITOR("Minos Inquisitor", List.of("inquisitor", "inq"), 0xFFD679FF),

    /** Counted in hits rather than in health for part of its life - see {@code MythMobTracker}. */
    KING("King Minos", List.of("king minos", "king"), 0xFFFFB454),

    /** Asks a riddle instead of fighting. See {@code SphinxAnswers}. */
    SPHINX("Sphinx", List.of("sphinx"), 0xFF7FE3C0),

    /** Shares the Inquisitor's rarity tier without sharing its reputation. */
    MANTICORE("Manticore", List.of("manticore", "manti"), 0xFF7FD96B);

    /**
     * Adjectives Hypixel prefixes onto a mythological creature's name for its variant tier.
     *
     * <p>Stripped before matching, so "Empyrean Minos Inquisitor" is still an Inquisitor. A list of
     * game facts, and one that will grow: an unrecognised prefix costs a missed match, which is why
     * the match is <i>contains</i> rather than <i>equals</i> and this list is only an optimisation
     * for the display name.
     */
    public static final List<String> VARIANT_PREFIXES = List.of(
            "Empyrean", "Exalted", "Runic", "Venerable", "Stalwart", "Blessed");

    private final String defaultName;
    private final List<String> aliases;
    private final int color;

    MythCreature(String defaultName, List<String> aliases, int color) {
        this.defaultName = defaultName;
        this.aliases = aliases;
        this.color = color;
    }

    /** The name this build ships with. Unverified against the live game - see the class note. */
    public String defaultName() {
        return defaultName;
    }

    /** The short forms another player is likely to type when sharing coordinates. */
    public List<String> aliases() {
        return aliases;
    }

    /** Marker colour, ARGB. */
    public int color() {
        return color;
    }

    /**
     * Whether {@code text} names this creature, given the player's configured override.
     *
     * <p>Contains rather than equals, because the nametag carries a level, a health readout and
     * whatever else Hypixel has decorated it with. The override wins outright when set: if the game
     * has renamed one of these, the player fixes it in the settings and nothing else has to change.
     */
    public boolean matches(String text, String override) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String needle = (override == null || override.isBlank() ? defaultName : override)
                .trim().toLowerCase(Locale.ROOT);
        return !needle.isEmpty() && text.toLowerCase(Locale.ROOT).contains(needle);
    }

    /**
     * Whether {@code word} is how someone would refer to this creature in a shared coordinate line.
     *
     * <p>Separate from {@link #matches} because the two read different things: that one reads a
     * nametag Hypixel wrote, this one reads a word a person typed, and people type "inq".
     */
    public boolean namedBy(String word, String override) {
        if (word == null) {
            return false;
        }
        String needle = word.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return false;
        }
        if (matches(needle, override)) {
            return true;
        }
        for (String alias : aliases) {
            if (alias.equals(needle)) {
                return true;
            }
        }
        return false;
    }

    /** "Empyrean Minos Inquisitor" -> "Minos Inquisitor". Display only; never used as identity. */
    public static String stripVariantPrefix(String name) {
        if (name == null) {
            return "";
        }
        String out = name.trim();
        for (String prefix : VARIANT_PREFIXES) {
            if (out.regionMatches(true, 0, prefix + " ", 0, prefix.length() + 1)) {
                return out.substring(prefix.length() + 1).trim();
            }
        }
        return out;
    }
}
