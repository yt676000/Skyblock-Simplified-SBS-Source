/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.hunting.model;

import sbs.modid.client.helper.rift.model.Certainty;

/**
 * A shard's rarity and how many of it an attribute takes to max.
 *
 * <p><b>Every one of these counts is {@link Certainty#WIKI} and is drawn as such.</b> They are not
 * in Hypixel's keyless items resource - checked on 2026-08-10, which lists none of the 320
 * {@code SHARD_*} products the Bazaar trades - and they are not in any other keyless source, so
 * nothing here has been verified against a live attribute menu. Attribute balance has been changed
 * more than once, and a syphon figure that is quietly out of date is the one number in this feature
 * that can cost somebody an attribute's worth of shards.
 *
 * <p>The counts are therefore used for <b>one purpose only</b>: working out the part of a stack that
 * is surplus <i>whatever</i> the player's current attribute level is - see
 * {@code ShardValuation#surplus}. That subtraction is a lower bound, so a wrong requirement here
 * makes the feature say "keep more than you need to", never "sell what you needed".
 */
public enum ShardRarity {

    COMMON("Common", 96, 0xFFFFFFFF),
    UNCOMMON("Uncommon", 64, 0xFF55FF55),
    RARE("Rare", 48, 0xFF5555FF),
    EPIC("Epic", 32, 0xFFAA00AA),
    LEGENDARY("Legendary", 24, 0xFFFFAA00),
    /** Anything the lore did not name - the count is unknown, not assumed. */
    UNKNOWN("Unknown", -1, 0xFFAAAAAA);

    /** How much any of these counts is worth trusting. One tag, because they came from one place. */
    public static final Certainty COUNT_CERTAINTY = Certainty.WIKI;

    private final String displayName;
    private final int toMax;
    private final int color;

    ShardRarity(String displayName, int toMax, int color) {
        this.displayName = displayName;
        this.toMax = toMax;
        this.color = color;
    }

    public String displayName() {
        return displayName;
    }

    /** Shards of this rarity needed to take one attribute from zero to max, or -1 when unknown. */
    public int toMax() {
        return toMax;
    }

    public int color() {
        return color;
    }

    public boolean known() {
        return toMax > 0;
    }

    /** The rarity a lore/name reading produced, mapped onto this scale; never {@code null}. */
    public static ShardRarity from(sbs.modid.client.core.item.Rarity rarity) {
        if (rarity == null) {
            return UNKNOWN;
        }
        return switch (rarity.name()) {
            case "COMMON" -> COMMON;
            case "UNCOMMON" -> UNCOMMON;
            case "RARE" -> RARE;
            case "EPIC" -> EPIC;
            // A shard above Legendary is not a thing today, but Mythic/Divine/Special exist on other
            // items and would otherwise fall to UNKNOWN silently. Treated as Legendary's count, which
            // is the smallest, so the surplus lower bound stays a lower bound.
            case "LEGENDARY", "MYTHIC", "DIVINE", "SPECIAL" -> LEGENDARY;
            default -> UNKNOWN;
        };
    }

    /** The rarity whose name matches {@code text} (a stored snapshot's string), or UNKNOWN. */
    public static ShardRarity byName(String text) {
        if (text != null) {
            for (ShardRarity rarity : values()) {
                if (rarity.name().equalsIgnoreCase(text)) {
                    return rarity;
                }
            }
        }
        return UNKNOWN;
    }
}
