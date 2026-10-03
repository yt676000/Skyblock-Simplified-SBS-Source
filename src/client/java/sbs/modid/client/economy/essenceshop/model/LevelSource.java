/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.essenceshop.model;

/**
 * Where a perk's current level was read from, and therefore how far it can be trusted.
 *
 * <p>The cost of maxing a perk is only as good as the level it is counted from, and the two places
 * that level can be read are not equally reliable. The menu's own progress line states it; a roman
 * numeral in the item's name might be the level you have or the level on offer, and nobody here has
 * watched which. So the weaker reading is still used - it is usually right, and refusing it would
 * blank most of the panel - but it is marked in the UI and it says so in the footer, the same way
 * the Rift numbers separate "watched happening" from "inferred".
 */
public enum LevelSource {

    /** Stated by the menu's own progress wording. Drawn normally. */
    LORE,

    /**
     * Inferred from a numeral in the perk's name. Drawn with a {@code ~} and counted in the
     * footer, because "Forbidden Strength III" may mean level 3 or the third level being offered.
     */
    NAME,

    /** Nothing readable. The row shows {@code ?} and stays out of the total entirely. */
    UNKNOWN
}
