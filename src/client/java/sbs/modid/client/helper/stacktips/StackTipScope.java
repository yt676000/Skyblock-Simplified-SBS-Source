/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.stacktips;

/** Where stack tips are drawn: over open menus, on the HUD hotbar, or both. */
public enum StackTipScope {
    CONTAINERS("Containers"),
    HOTBAR("Hotbar"),
    BOTH("Both");

    private final String displayName;

    StackTipScope(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public boolean containers() {
        return this != HOTBAR;
    }

    public boolean hotbar() {
        return this != CONTAINERS;
    }
}
