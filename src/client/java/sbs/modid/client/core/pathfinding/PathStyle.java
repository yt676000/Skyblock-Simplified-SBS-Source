/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.pathfinding;

/**
 * How the route is drawn.
 *
 * <ul>
 *   <li>{@link #CUBES} – a trail of small glowing cubes. Has no corner joins at all, which is what
 *       the line style struggles with: two thick segments meeting at a right angle leave a visibly
 *       broken, notched corner.</li>
 *   <li>{@link #LINE} – the connected conduit, with the pulse and direction chevrons.</li>
 *   <li>{@link #CUBES_AND_LINE} – both: the line carries the direction, the cubes hide its corners.</li>
 * </ul>
 */
public enum PathStyle {

    CUBES("Cubes"),
    LINE("Line"),
    CUBES_AND_LINE("Cubes + Line");

    private final String displayName;

    PathStyle(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** Whether the cube trail should be drawn in this style. */
    public boolean drawsCubes() {
        return this != LINE;
    }

    /** Whether the connecting line should be drawn in this style. */
    public boolean drawsLine() {
        return this != CUBES;
    }

    public PathStyle next() {
        PathStyle[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
