/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.pathfinding;

/**
 * How large a waypoint's label is drawn, as a factor on the GUI-scale text.
 *
 * <p>{@link #NORMAL} is 1.0, the size every label had before this existed, and is the default.
 */
public enum MarkerLabelSize {
    SMALL("Small", 0.75f),
    NORMAL("Normal", 1.0f),
    LARGE("Large", 1.5f);

    private final String label;
    private final float scale;

    MarkerLabelSize(String label, float scale) {
        this.label = label;
        this.scale = scale;
    }

    /** The word on the settings switch. */
    public String label() {
        return label;
    }

    public float scale() {
        return scale;
    }
}
