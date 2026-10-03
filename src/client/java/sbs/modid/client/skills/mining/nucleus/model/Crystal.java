/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.nucleus.model;

import java.util.Locale;

/**
 * The five crystals placed at the Crystal Nucleus, in the order the card shows them.
 *
 * <p>{@link #name()} is the stable id stored in {@code nucleus_runs.json}; never rename a constant.
 */
public enum Crystal {
    JADE("Jade", "J"),
    AMBER("Amber", "A"),
    AMETHYST("Amethyst", "Am"),
    SAPPHIRE("Sapphire", "S"),
    TOPAZ("Topaz", "T");

    /** How far a crystal has come this run. Stored by name. */
    public enum State {
        NONE, FOUND, PLACED
    }

    private final String displayName;
    private final String shortName;

    Crystal(String displayName, String shortName) {
        this.displayName = displayName;
        this.shortName = shortName;
    }

    public String displayName() {
        return displayName;
    }

    /** The label on the card - one or two letters, so five fit on one line. */
    public String shortName() {
        return shortName;
    }

    /** {@code "Sapphire"} to {@link #SAPPHIRE}, case-insensitive; {@code null} for anything else. */
    public static Crystal byName(String name) {
        if (name == null) {
            return null;
        }
        String wanted = name.trim().toLowerCase(Locale.ROOT);
        for (Crystal crystal : values()) {
            if (crystal.displayName.toLowerCase(Locale.ROOT).equals(wanted)) {
                return crystal;
            }
        }
        return null;
    }
}
