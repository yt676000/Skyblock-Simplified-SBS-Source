/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.pathfinding;

/**
 * How the pathfinder is allowed to move.
 *
 * <ul>
 *   <li>{@link #AUTO} – follow the player: flying routes through the air, otherwise walk with
 *       whatever jump height they currently have. This is what you want in normal use.</li>
 *   <li>{@link #WALK} / {@link #FLY} – force one mode. Test-only overrides, so a route can be
 *       checked without having to actually take off or drink a potion.</li>
 * </ul>
 */
public enum PathMode {

    AUTO("Auto"),
    WALK("Walk"),
    FLY("Fly");

    private final String displayName;

    PathMode(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public PathMode next() {
        PathMode[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
