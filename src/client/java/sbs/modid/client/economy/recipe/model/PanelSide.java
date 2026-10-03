/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.recipe.model;

/**
 * Which side of the open inventory the Recipe Viewer item list is rendered on.
 */
public enum PanelSide {

    RIGHT("Right"),
    LEFT("Left");

    private final String displayName;

    PanelSide(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public PanelSide next() {
        return this == RIGHT ? LEFT : RIGHT;
    }
}
