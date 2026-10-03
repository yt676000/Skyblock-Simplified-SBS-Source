/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.essenceshop.model;

import java.util.List;

/**
 * One perk as the cost table describes it: a stable key, the name the menu shows, and the price of
 * every single level.
 *
 * @param key    the table's own perk key ({@code permanent_strength}) - stable, and what any stored
 *               state must be keyed on. Never the display name, which Hypixel can reword.
 * @param name   the display name used to find this perk's slot in the open menu
 * @param costs  {@code costs.get(i)} is the price of the step from level {@code i} to {@code i+1},
 *               so the list length is the maximum level
 */
public record ShopPerk(String key, String name, List<Long> costs) {

    public ShopPerk {
        costs = List.copyOf(costs);
    }

    public int maxLevel() {
        return costs.size();
    }

    /**
     * What is still owed to reach the top from {@code level}, in essence.
     *
     * <p>A level at or above the maximum owes nothing; a negative level is treated as zero, so a
     * misread can never produce a total larger than buying the perk from scratch.
     */
    public long remainingFrom(int level) {
        long sum = 0;
        for (int i = Math.max(0, level); i < costs.size(); i++) {
            sum += costs.get(i);
        }
        return sum;
    }
}
