/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.fishing.logic;

import sbs.modid.client.skills.fishing.model.FishingData;
import sbs.modid.client.skills.fishing.model.SeaCreatureRarity;
import sbs.modid.client.skills.fishing.render.FishingAlert;
/**
 * Which sea creature spawns raise the {@link FishingAlert}.
 *
 * <p>The point of the narrow setting: an alert that fires for every Squid is one you stop reading,
 * which costs you the Jawbus it was meant to catch.
 */
public enum SpawnAlertFilter {

    /** Every spawn, filler included. */
    ALL("All Creatures"),

    /** Only the trophies – {@link SeaCreatureRarity#LEGENDARY} and up. */
    MYTHIC_AND_LEGENDARY("Mythic & Legendary");

    private final String displayName;

    SpawnAlertFilter(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** The next mode in the cycle (used by the settings cycle button). */
    public SpawnAlertFilter next() {
        SpawnAlertFilter[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    /** Whether {@code creature}'s spawn is worth an alert under this filter. */
    public boolean allows(String creature) {
        return switch (this) {
            case ALL -> true;
            case MYTHIC_AND_LEGENDARY ->
                    FishingData.rarityOf(creature).atLeast(SeaCreatureRarity.LEGENDARY);
        };
    }
}
