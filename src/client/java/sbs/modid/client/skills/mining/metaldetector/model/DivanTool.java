/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.metaldetector.model;

/**
 * The four scavenged tools buried in the Mines of Divan, each owed to one Keeper. Returning all four
 * makes the Keepers reveal the Jade Crystal.
 *
 * <p>The names are the item names as the chat prints them after {@code Scavenged}, and the Keeper is
 * the word after {@code Keeper of} - both CONFIRMED from chat logs, see
 * {@code docs/features/metal-detector.md}. Never rename a constant: the order is the checklist's
 * row order and the name is what the tests key on.
 */
public enum DivanTool {
    GOLDEN_HAMMER("Golden Hammer", "Gold"),
    EMERALD_HAMMER("Emerald Hammer", "Emerald"),
    DIAMOND_AXE("Diamond Axe", "Diamond"),
    LAPIS_SWORD("Lapis Sword", "Lapis");

    private final String displayName;
    private final String keeper;

    DivanTool(String displayName, String keeper) {
        this.displayName = displayName;
        this.keeper = keeper;
    }

    /** {@code "Diamond Axe"} - the checklist row. */
    public String displayName() {
        return displayName;
    }

    /** {@code "Scavenged Diamond Axe"} - the item as chat names it. */
    public String itemName() {
        return "Scavenged " + displayName;
    }

    /** {@code "Diamond"}, as in {@code Keeper of Diamond}. */
    public String keeper() {
        return keeper;
    }

    /** {@code "Diamond Axe"} to {@link #DIAMOND_AXE}, case-insensitive; {@code null} otherwise. */
    public static DivanTool byName(String name) {
        if (name == null) {
            return null;
        }
        String wanted = name.trim();
        for (DivanTool tool : values()) {
            if (tool.displayName.equalsIgnoreCase(wanted)) {
                return tool;
            }
        }
        return null;
    }
}
