/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.logic;

import sbs.modid.client.skills.mining.model.GemstoneTier;
import sbs.modid.client.skills.mining.model.GemstoneType;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the names and ids gemstones appear under into a {@code (type, tier)} pair, and back.
 *
 * <p>Two spellings have to be understood, because the feature reads from two places. The sack
 * breakdown gives display names ("Rough Jade Gemstone"); the Bazaar gives ids
 * ({@code ROUGH_JADE_GEM}). Both forms are regular across all sixty products - verified against the
 * live Bazaar product list and the items resource - so this is a parse rather than a table, and a
 * gemstone kind added later works without a code change.
 *
 * <p><b>Deliberately strict.</b> Only the exact three-word shape counts. "Gemstone Mixture", "Glossy
 * Gemstone", "Dwarven Geode" and "Jaderald" all contain gemstone words and none of them is a
 * gemstone drop; matching loosely would book them into a tier and quietly inflate the profit figure
 * with items that are not on the ladder at all.
 */
public final class GemstoneCatalog {

    /** "Rough Jade Gemstone" - tier word, kind word, the literal "Gemstone". */
    private static final Pattern DISPLAY_NAME = Pattern.compile(
            "^(Rough|Flawed|Fine|Flawless|Perfect)\\s+([A-Za-z]+)\\s+Gemstone$",
            Pattern.CASE_INSENSITIVE);

    /** "ROUGH_JADE_GEM". */
    private static final Pattern ITEM_ID = Pattern.compile(
            "^(ROUGH|FLAWED|FINE|FLAWLESS|PERFECT)_([A-Z]+)_GEM$");

    private GemstoneCatalog() {
    }

    /** One gemstone product: which kind, which grade. */
    public record Gem(GemstoneType type, GemstoneTier tier) {

        /** The Bazaar product id, e.g. {@code ROUGH_JADE_GEM}. */
        public String bazaarId() {
            return tier.idPrefix() + "_" + type.idPart() + "_GEM";
        }

        /** "Rough Jade", the form a HUD row wants - the word "Gemstone" is the card's title. */
        public String shortName() {
            return tier.displayName() + " " + type.displayName();
        }

        /** The same gem one grade up, or {@code null} at the top of the ladder. */
        public Gem next() {
            GemstoneTier up = tier.next();
            return up == null ? null : new Gem(type, up);
        }
    }

    /** The gem a sack-breakdown display name refers to, or {@code null} when it is not one. */
    public static Gem byDisplayName(String name) {
        if (name == null) {
            return null;
        }
        Matcher matcher = DISPLAY_NAME.matcher(name.trim());
        if (!matcher.matches()) {
            return null;
        }
        return build(matcher.group(1), matcher.group(2));
    }

    /** The gem a Bazaar / SkyBlock item id refers to, or {@code null} when it is not one. */
    public static Gem byItemId(String id) {
        if (id == null) {
            return null;
        }
        Matcher matcher = ITEM_ID.matcher(id.trim().toUpperCase(Locale.ROOT));
        if (!matcher.matches()) {
            return null;
        }
        return build(matcher.group(1), matcher.group(2));
    }

    private static Gem build(String tierWord, String typeWord) {
        GemstoneType type = GemstoneType.byName(typeWord);
        if (type == null) {
            return null;   // a real gemstone-shaped name for a kind we do not know: not ours to count
        }
        for (GemstoneTier tier : GemstoneTier.values()) {
            if (tier.displayName().equalsIgnoreCase(tierWord)) {
                return new Gem(type, tier);
            }
        }
        return null;
    }
}
