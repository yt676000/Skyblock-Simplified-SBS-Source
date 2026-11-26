/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.fishing.model;

import java.util.Set;

/** How the party spawn alert uses its creature list. */
public enum PartyAlertListMode {
    /** Every shared creature alerts; the list is ignored. */
    ALL("All"),
    /** Only creatures on the list alert. */
    WHITELIST("Whitelist"),
    /** Every creature alerts except those on the list. */
    BLACKLIST("Blacklist");

    private final String displayName;

    PartyAlertListMode(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** Whether {@code creature} alerts; {@code list} holds lower-case names. */
    public boolean allows(String creature, Set<String> list) {
        String key = creature.toLowerCase(java.util.Locale.ROOT);
        return switch (this) {
            case ALL -> true;
            case WHITELIST -> list.contains(key);
            case BLACKLIST -> !list.contains(key);
        };
    }
}
