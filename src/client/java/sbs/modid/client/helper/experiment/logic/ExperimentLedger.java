/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.experiment.logic;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Experimentation Table profit: claims per game and what they paid, as counts. Pure and
 * Gson-serialisable; items are priced when asked ({@link #value}), never when booked, so the value
 * follows the market.
 *
 * <p>Income is every priced reward. Experience is counted but has no coin value. The only cost is
 * the player's configured cost per session (Bits, coins or anything else they want to charge the
 * table with), charged once per session - the table itself is not confirmed to consume any item, so
 * nothing is deducted for one. A session is a run of claims with no gap over {@link #SESSION_GAP_MS}:
 * the three games of one visit are one session, the next visit is the next.
 */
public final class ExperimentLedger {

    /** A claim this long after the previous one starts a new session. */
    public static final long SESSION_GAP_MS = 30L * 60L * 1000L;

    /** Prices a reward. A {@code null} answer means "no market knows it" and counts as unpriced. */
    public interface Pricer {
        Long item(String itemId);

        Long book(String enchant, int tier);
    }

    /** A priced total: the coin value and how many reward units had no price. */
    public record Value(long coins, long unpriced) {
    }

    private Map<ExperimentRewardParser.Game, Long> claims = new EnumMap<>(ExperimentRewardParser.Game.class);
    private long experience;
    private long sessions;
    private long lastClaimAt;
    /** Item id to count. */
    private Map<String, Long> items = new LinkedHashMap<>();
    /** {@code enchant:tier} to count. */
    private Map<String, Long> books = new LinkedHashMap<>();

    /** One claim at {@code nowMs} (wall clock); opens a new session after a long enough gap. */
    public void claim(ExperimentRewardParser.Game game, long nowMs) {
        if (sessions == 0 || nowMs - lastClaimAt > SESSION_GAP_MS) {
            sessions++;
        }
        lastClaimAt = nowMs;
        claims.merge(game, 1L, Long::sum);
    }

    public void reward(ExperimentRewardParser.Reward reward) {
        switch (reward.kind()) {
            case XP -> experience += reward.amount();
            case ITEM -> items.merge(reward.itemId(), reward.amount(), Long::sum);
            case BOOK -> books.merge(reward.enchant() + ":" + reward.tier(), reward.amount(), Long::sum);
        }
    }

    public long claims() {
        long total = 0;
        for (long n : claims.values()) {
            total += n;
        }
        return total;
    }

    public long claims(ExperimentRewardParser.Game game) {
        return claims.getOrDefault(game, 0L);
    }

    public long sessions() {
        return sessions;
    }

    public long experience() {
        return experience;
    }

    public Map<String, Long> items() {
        return Map.copyOf(items);
    }

    public Map<String, Long> books() {
        return Map.copyOf(books);
    }

    /** The value of every reward booked so far. */
    public Value value(Pricer pricer) {
        long coins = 0;
        long unpriced = 0;
        for (Map.Entry<String, Long> e : items.entrySet()) {
            Long price = pricer.item(e.getKey());
            if (price == null) {
                unpriced += e.getValue();
            } else {
                coins += price * e.getValue();
            }
        }
        for (Map.Entry<String, Long> e : books.entrySet()) {
            int split = e.getKey().lastIndexOf(':');
            Long price = pricer.book(e.getKey().substring(0, split),
                    Integer.parseInt(e.getKey().substring(split + 1)));
            if (price == null) {
                unpriced += e.getValue();
            } else {
                coins += price * e.getValue();
            }
        }
        return new Value(coins, unpriced);
    }

    /** What the sessions in this ledger cost at {@code sessionCost} each. */
    public long cost(long sessionCost) {
        return sessions * Math.max(0L, sessionCost);
    }

    /** Value minus the cost of every session in this ledger. */
    public long net(Pricer pricer, long sessionCost) {
        return value(pricer).coins() - cost(sessionCost);
    }

    /** Net per claim, or 0 before the first claim. */
    public long netPerClaim(Pricer pricer, long sessionCost) {
        long n = claims();
        return n == 0 ? 0L : net(pricer, sessionCost) / n;
    }
}
