/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.trophyfish.model;

import java.util.Locale;

/** The four trophy fish tiers, weakest first - the order of every count array in the tracker. */
public enum TrophyTier {
    BRONZE("Bronze", 0xFFCD7F32),
    SILVER("Silver", 0xFFC0C0C0),
    GOLD("Gold", 0xFFFFAA00),
    DIAMOND("Diamond", 0xFF55FFFF);

    private final String label;
    private final int color;

    TrophyTier(String label, int color) {
        this.label = label;
        this.color = color;
    }

    public String label() {
        return label;
    }

    /** One letter, for the grid's column headers and the compact list. */
    public String letter() {
        return label.substring(0, 1);
    }

    public int color() {
        return color;
    }

    /** The tier a printed word names ("DIAMOND", "Gold"), or {@code null}. */
    public static TrophyTier byWord(String word) {
        if (word == null) {
            return null;
        }
        String upper = word.trim().toUpperCase(Locale.ROOT);
        for (TrophyTier tier : values()) {
            if (tier.name().equals(upper)) {
                return tier;
            }
        }
        return null;
    }
}
