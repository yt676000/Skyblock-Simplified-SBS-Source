/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.itemprotection.model;

/** How a protected slot is marked. */
public enum IndicatorStyle {

    /** A one-pixel frame around the slot in the configured colour. */
    BORDER("Border"),

    /** A small shield in the slot's top-left corner, clear of the stack count. */
    ICON("Icon"),

    BOTH("Both");

    private final String displayName;

    IndicatorStyle(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public boolean drawsBorder() {
        return this == BORDER || this == BOTH;
    }

    public boolean drawsIcon() {
        return this == ICON || this == BOTH;
    }
}
