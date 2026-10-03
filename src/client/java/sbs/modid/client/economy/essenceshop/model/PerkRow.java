/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.essenceshop.model;

/**
 * One line of the overview: a perk from the cost table, the level the open menu says the player is
 * at, and what maxing it still costs.
 *
 * @param perk      the table entry, carrying the full cost curve
 * @param level     the current level, or {@code -1} when it could not be read
 * @param source    how the level was read - see {@link LevelSource}
 * @param remaining essence still owed to reach the maximum, or {@code -1} when the level is unknown
 */
public record PerkRow(ShopPerk perk, int level, LevelSource source, long remaining) {

    /** A perk whose slot was found and whose level was read. */
    public static PerkRow known(ShopPerk perk, int level, LevelSource source) {
        return new PerkRow(perk, level, source, perk.remainingFrom(level));
    }

    /**
     * A perk the table knows but the open menu did not yield a level for - either its slot was not
     * found or nothing in it could be read. Costed as unknown rather than as zero.
     */
    public static PerkRow unknown(ShopPerk perk) {
        return new PerkRow(perk, -1, LevelSource.UNKNOWN, -1);
    }

    public boolean counted() {
        return source != LevelSource.UNKNOWN;
    }

    public boolean maxed() {
        return counted() && level >= perk.maxLevel();
    }

    /** {@code "3/5"}, {@code "~3/5"} for an inferred level, {@code "?/5"} when it is unknown. */
    public String levelText() {
        String prefix = switch (source) {
            case LORE -> "";
            case NAME -> "~";
            case UNKNOWN -> "";
        };
        String current = counted() ? String.valueOf(Math.min(level, perk.maxLevel())) : "?";
        return prefix + current + "/" + perk.maxLevel();
    }
}
