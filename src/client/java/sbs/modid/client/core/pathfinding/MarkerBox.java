/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.pathfinding;

/**
 * How a waypoint's marker box is drawn.
 *
 * <p>{@link #OUTLINE} is what every waypoint drew before this existed - a bloom shell and a crisp
 * core, edges only - and is the default, so a publisher that says nothing is unchanged. The fill is
 * {@code WorldRender.fillBox}: the box's projected bounding rectangle at a low alpha, which reads as
 * a glow on the block rather than a solid cube.
 */
public enum MarkerBox {
    OUTLINE("Outline"),
    FILLED("Filled"),
    BOTH("Both");

    private final String label;

    MarkerBox(String label) {
        this.label = label;
    }

    /** The word on the settings switch. */
    public String label() {
        return label;
    }

    public boolean outline() {
        return this != FILLED;
    }

    public boolean filled() {
        return this != OUTLINE;
    }
}
