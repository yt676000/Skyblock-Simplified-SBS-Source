/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.itemprotection.model;

/** Whether a protected item refuses an action outright, or asks once and then lets it through. */
public enum ProtectionMode {

    /** Refuse every time. Unprotect the item to act on it. */
    HARD_BLOCK("Hard Block"),

    /** Refuse once, then let an identical repeat inside the confirm window through. */
    CONFIRM("Confirm");

    private final String displayName;

    ProtectionMode(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
