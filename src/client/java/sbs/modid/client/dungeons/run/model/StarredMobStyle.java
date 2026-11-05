/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.run.model;

/**
 * How a starred (✯) dungeon mob is highlighted: the box around its hitbox, the vanilla silhouette
 * outline in the same colour, or both. Either way only mobs that pass the line-of-sight test.
 */
public enum StarredMobStyle {

    HITBOX("Hitbox"),
    OUTLINE("Outline"),
    BOTH("Both");

    private final String displayName;

    StarredMobStyle(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** Whether the hitbox edges are drawn. */
    public boolean box() {
        return this != OUTLINE;
    }

    /** Whether the silhouette outline is drawn. */
    public boolean outline() {
        return this != HITBOX;
    }
}
