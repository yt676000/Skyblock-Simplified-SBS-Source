/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.farming.net;

import sbs.modid.client.skills.farming.session.FarmingSessionClock;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * One farming session's income and costs: crops, pest loot and visitor rewards in, visitor items
 * and consumables out. Pure - fed counts and timestamps by {@link FarmingNetTracker}, priced only
 * when {@link #totals} is asked, so a moving Bazaar never leaves a stale figure behind.
 *
 * <p><b>Active time</b> is a {@link FarmingSessionClock}'s: the gaps between counted crop breaks,
 * each only when it is at most the pause threshold. The clock is never ticked, so it never ends a
 * session - this ledger lasts until it is reset. Pest and visitor income is counted whenever it
 * arrives; only the per-hour divisor is restricted to time spent farming.
 *
 * <p>Visitor item costs are fixed at the moment of acceptance (the items are gone; what they were
 * worth then is what was paid), crops and consumables are priced on read.
 */
public final class FarmingNetLedger {

    /** Below this much active time there is no per-hour figure - one minute says nothing yet. */
    static final long MIN_RATE_MS = 60_000L;

    /** The figures the card shows, all in coins. {@code unpriced} marks a total missing something. */
    public record Totals(long crops, long pests, long visitorRewards, long visitorItems, long consumables,
                         long net, long perHour, long activeMs, int visitorCount, long copper,
                         Map<String, Long> cropCoins, boolean unpriced) {

        /** Visitor rewards minus the items given, the card's single "Visitors" figure. */
        public long visitorNet() {
            return visitorRewards - visitorItems;
        }

        /** Everything paid out: visitor items given plus consumables. */
        public long costs() {
            return visitorItems + consumables;
        }
    }

    /** How the totals are priced and what they include - every input the card's key needs. */
    public record Pricing(ToDoubleFunction<String> cropPrice, ToDoubleFunction<String> costPrice,
                          double coinsPerCopper, boolean includeVisitors, long extraCostPerHour) {
    }

    private final FarmingSessionClock clock;
    private final Map<String, Long> crops = new LinkedHashMap<>();
    private final Map<String, Long> consumables = new LinkedHashMap<>();
    private long pestCoins;
    private int visitors;
    private long copper;
    private long visitorItemCost;
    private boolean visitorUnpriced;
    private int revision;

    /** @param pauseMs a gap between crop breaks longer than this is a pause, not farming */
    public FarmingNetLedger(long pauseMs) {
        this.clock = new FarmingSessionClock(pauseMs, Long.MAX_VALUE / 4);
    }

    /** Bumped on every change - with the pricing inputs, the card's rebuild key. */
    public int revision() {
        return revision;
    }

    public long activeMs() {
        return clock.activeMs();
    }

    public boolean isEmpty() {
        return crops.isEmpty() && consumables.isEmpty() && pestCoins == 0 && visitors == 0;
    }

    // ------------------------------------------------------------------ sources

    /** A counted crop break (farming tool in hand, on a farming island). */
    public void onFarmed(long now) {
        long before = clock.activeMs();
        clock.onCropBroken(now);
        if (clock.activeMs() != before) {
            revision++;
        }
    }

    /** Crops gained, keyed by Bazaar id. */
    public void addCrops(String bazaarId, long count) {
        if (count > 0) {
            crops.merge(bazaarId, count, Long::sum);
            revision++;
        }
    }

    /** Pest loot value this session, as the Pest Profit ledger prices it - a level, not an increment. */
    public void setPestCoins(long coins) {
        long value = Math.max(0, coins);
        if (value != pestCoins) {
            pestCoins = value;
            revision++;
        }
    }

    /**
     * A visitor served.
     *
     * @param itemCost what the items handed over were worth when they were given
     * @param priced   false when at least one of those items had no price
     * @param copper   the copper the offer paid, -1 when the menu never showed it
     */
    public void onVisitor(long itemCost, boolean priced, int copper) {
        visitors++;
        visitorItemCost += Math.max(0, itemCost);
        visitorUnpriced |= !priced || copper < 0;
        this.copper += Math.max(0, copper);
        revision++;
    }

    /** A consumable used up (a spray's material and so on), keyed by item id. */
    public void addConsumable(String itemId, long count) {
        if (count > 0) {
            consumables.merge(itemId, count, Long::sum);
            revision++;
        }
    }

    public void reset() {
        clock.endManually();
        crops.clear();
        consumables.clear();
        pestCoins = 0;
        visitors = 0;
        copper = 0;
        visitorItemCost = 0;
        visitorUnpriced = false;
        revision++;
    }

    // ------------------------------------------------------------------ read

    /**
     * Net = crops + pests + visitor rewards - visitor items - consumables - the configured hourly
     * cost over the active time. A price of zero or less counts nothing and marks the total unpriced.
     */
    public Totals totals(Pricing pricing) {
        boolean unpriced = false;
        Map<String, Long> cropCoins = new LinkedHashMap<>();
        long cropTotal = 0;
        for (Map.Entry<String, Long> e : crops.entrySet()) {
            double price = pricing.cropPrice().applyAsDouble(e.getKey());
            if (price <= 0) {
                unpriced = true;
            }
            long coins = Math.round(Math.max(0, price) * e.getValue());
            cropCoins.put(e.getKey(), coins);
            cropTotal += coins;
        }
        long consumableTotal = 0;
        for (Map.Entry<String, Long> e : consumables.entrySet()) {
            double price = pricing.costPrice().applyAsDouble(e.getKey());
            if (price <= 0) {
                unpriced = true;
            }
            consumableTotal += Math.round(Math.max(0, price) * e.getValue());
        }
        long active = clock.activeMs();
        consumableTotal += pricing.extraCostPerHour() * active / 3_600_000L;

        long rewards = 0;
        long given = 0;
        if (pricing.includeVisitors()) {
            given = visitorItemCost;
            if (copper > 0 && pricing.coinsPerCopper() <= 0) {
                unpriced = true;   // copper earned but the player has not said what it is worth
            }
            rewards = Math.round(copper * Math.max(0, pricing.coinsPerCopper()));
            unpriced |= visitorUnpriced;
        }
        long net = cropTotal + pestCoins + rewards - given - consumableTotal;
        long perHour = active < MIN_RATE_MS ? 0 : Math.round(net * 3_600_000d / active);
        return new Totals(cropTotal, pestCoins, rewards, given, consumableTotal, net, perHour, active,
                pricing.includeVisitors() ? visitors : 0, pricing.includeVisitors() ? copper : 0,
                Collections.unmodifiableMap(cropCoins), unpriced);
    }
}
