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
 * Everything the panel draws for one open essence shop, computed once per menu revision.
 *
 * @param type      which essence this shop spends
 * @param rows      every perk the cost table knows for this shop, in table order
 * @param total     essence to max every counted perk; rows with an unknown level are <b>not</b> in it
 * @param balance   the player's essence, or {@code -1} when the menu did not state it
 * @param computedAt wall clock of the computation, so a stale panel can say how stale it is
 */
public record ShopOverview(EssenceType type, List<PerkRow> rows, long total, long balance,
                           long computedAt) {

    public ShopOverview {
        rows = List.copyOf(rows);
    }

    /** Rows left out of {@link #total} because no level could be read. */
    public int unknownCount() {
        return (int) rows.stream().filter(row -> !row.counted()).count();
    }

    /** Rows in {@link #total} whose level was inferred from the item name rather than stated. */
    public int inferredCount() {
        return (int) rows.stream().filter(row -> row.source() == LevelSource.NAME).count();
    }

    /** Perks with nothing left to buy - shown, but contributing zero. */
    public int maxedCount() {
        return (int) rows.stream().filter(PerkRow::maxed).count();
    }

    /**
     * Whether {@link #total} is the whole answer. It is not the moment one perk could not be read,
     * and the panel says so rather than letting a short total pass for a complete one.
     */
    public boolean complete() {
        return unknownCount() == 0;
    }

    /** What is still missing after spending everything on hand, or {@code -1} with no balance. */
    public long shortfall() {
        return balance < 0 ? -1 : Math.max(0, total - balance);
    }

    public boolean empty() {
        return rows.isEmpty();
    }
}
