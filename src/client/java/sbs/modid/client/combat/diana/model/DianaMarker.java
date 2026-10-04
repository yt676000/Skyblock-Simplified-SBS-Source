/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.diana.model;

import java.util.Locale;

/**
 * The six kinds of marker the Diana toolkit puts in the world, each with its own style.
 *
 * <p>{@link #drawsThroughWallsToday} records which types already drew through terrain before the
 * appearance settings existed. The style's Through Walls switch can only take that away: a type
 * whose flag is {@code false} neither offers the row nor honours a stored {@code true}, so the
 * settings can never add a see-through marker the feature did not already have.
 */
public enum DianaMarker {
    START_BURROW("Start Burrow", true),
    MOB_BURROW("Mob Burrow", true),
    TREASURE_BURROW("Treasure Burrow", true),
    GUESS("Guess", true),
    RARE_CREATURE("Rare Creature", true),
    SHARED_CREATURE("Shared Creature", true);

    private final String displayName;
    private final boolean drawsThroughWallsToday;

    DianaMarker(String displayName, boolean drawsThroughWallsToday) {
        this.displayName = displayName;
        this.drawsThroughWallsToday = drawsThroughWallsToday;
    }

    public String displayName() {
        return displayName;
    }

    public boolean drawsThroughWallsToday() {
        return drawsThroughWallsToday;
    }

    /** The settings-row anchor prefix: {@code start_burrow}, {@code guess}, ... */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}
