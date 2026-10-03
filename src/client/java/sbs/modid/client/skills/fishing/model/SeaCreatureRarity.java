/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.fishing.model;

/**
 * How rare a sea creature is, in the ladder SkyBlock uses everywhere else.
 *
 * <p>Hypixel does not tag sea creatures with a rarity the client can read, so the tiers are a
 * judgement call kept as a table in {@link FishingData}. The order is what matters here: the spawn
 * alert filters on "this tier or better".
 *
 * <p>{@link #UNKNOWN} is not a tier the game has – it is what a creature detected by its nametag
 * alone gets when this build has never heard of the name (a new fishing area's creatures, before
 * anyone has added them to the table). It deliberately sorts <b>below</b> COMMON, so a "this tier
 * or better" filter can never let an unrecognised name through by accident; whether the announcer
 * speaks for one is its own separate, off-by-default decision.
 */
public enum SeaCreatureRarity {

    UNKNOWN("Unknown", 0xFFAAAAAA, "§7"),
    COMMON("Common", 0xFFFFFFFF, "§f"),
    RARE("Rare", 0xFF5555FF, "§9"),
    EPIC("Epic", 0xFFAA00AA, "§5"),
    LEGENDARY("Legendary", 0xFFFFAA00, "§6"),
    MYTHIC("Mythic", 0xFFFF55FF, "§d");

    private final String displayName;
    private final int argb;
    private final String code;

    SeaCreatureRarity(String displayName, int argb, String code) {
        this.displayName = displayName;
        this.argb = argb;
        this.code = code;
    }

    /** The tier as the player reads it – what the {@code {rarity}} placeholder expands to. */
    public String displayName() {
        return displayName;
    }

    /**
     * ARGB, for a highlight box or an on-screen title. These are
     * {@link sbs.modid.client.core.item.Rarity}'s own values, so a Mythic sea creature is the same
     * pink as a Mythic item everywhere else in the mod rather than a second palette that drifts
     * from it.
     */
    public int argb() {
        return argb;
    }

    /** The legacy § code for the same colour – what a chat line is painted with. */
    public String code() {
        return code;
    }

    /** Whether this tier is at least {@code floor} – the alert filter's whole question. */
    public boolean atLeast(SeaCreatureRarity floor) {
        return ordinal() >= floor.ordinal();
    }
}
