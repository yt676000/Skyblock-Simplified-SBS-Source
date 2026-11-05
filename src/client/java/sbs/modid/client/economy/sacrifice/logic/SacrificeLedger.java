/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.sacrifice.logic;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The counts behind the Sacrifice Altar card and the profit sum over them. Pure and Gson-friendly:
 * the all-time copy is the profile file.
 *
 * <p>Two kinds of value, on purpose. What a sacrificed item was worth is fixed when it is given
 * up: the exact stack (reforge, enchants) is gone afterwards and cannot be appraised again. The
 * essence and bonus loot are counted and priced at display time, so the net follows the market
 * like the other profit cards. "Best" is the one place a whole sacrifice is valued at once, with the
 * prices of that moment.
 */
public final class SacrificeLedger {

    /** One completed sacrifice, as it is handed in: the cost already valued, the gains as counts. */
    public record Sacrifice(String item, int essence, long cost, boolean costApprox, List<Bonus> bonuses) {
    }

    /** One bonus drop: price id, display name, count. */
    public record Bonus(String id, String name, int count) {
    }

    public long sacrifices;
    public long essence;
    /** Coins the sacrificed items were worth when they went. */
    public long cost;
    /** Sacrifices whose cost is the base item's price by name, not the exact stack's. */
    public long approxCosts;
    /** Price id → count of every own bonus drop. */
    public Map<String, Long> bonus = new LinkedHashMap<>();
    /** Price id → display name, for the card. */
    public Map<String, String> bonusNames = new LinkedHashMap<>();
    public String bestItem;
    public long bestNet;

    /**
     * Adds one sacrifice. {@code price} gives the current unit price of an id ({@code null} when
     * unknown) and is used only for the best-sacrifice figure.
     */
    public void add(Sacrifice s, Long essencePrice, Function<String, Long> price) {
        sacrifices++;
        essence += s.essence();
        cost += s.cost();
        if (s.costApprox()) {
            approxCosts++;
        }
        long income = s.essence() * orZero(essencePrice);
        for (Bonus b : s.bonuses()) {
            addBonus(b);
            income += b.count() * orZero(price.apply(b.id()));
        }
        long net = income - s.cost();
        if (bestItem == null || net > bestNet) {
            bestItem = s.item();
            bestNet = net;
        }
    }

    /** A bonus drop that arrived after its sacrifice was committed: counted, outside "best". */
    public void addBonus(Bonus b) {
        bonus().merge(b.id(), (long) b.count(), Long::sum);
        bonusNames().putIfAbsent(b.id(), b.name());
    }

    /** Essence plus bonus loot at today's prices; an unknown price counts as 0. */
    public long income(Long essencePrice, Function<String, Long> price) {
        long income = essence * orZero(essencePrice);
        for (Map.Entry<String, Long> e : bonus().entrySet()) {
            income += e.getValue() * orZero(price.apply(e.getKey()));
        }
        return income;
    }

    public long net(Long essencePrice, Function<String, Long> price) {
        return income(essencePrice, price) - cost;
    }

    public Map<String, Long> bonus() {
        if (bonus == null) {
            bonus = new LinkedHashMap<>();
        }
        return bonus;
    }

    public Map<String, String> bonusNames() {
        if (bonusNames == null) {
            bonusNames = new LinkedHashMap<>();
        }
        return bonusNames;
    }

    private static long orZero(Long price) {
        return price == null ? 0L : price;
    }
}
