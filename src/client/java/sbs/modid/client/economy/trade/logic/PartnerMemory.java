/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.trade.logic;

import sbs.modid.client.economy.trade.model.ItemPrint;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * What each trade partner has shown, for catching bait &amp; switch across trades: the real item in
 * one trade (or in chat), cancel, new trade, a look-alike worth a fraction.
 *
 * <p>In memory only - nothing reaches the disk - and every entry expires after the retention time.
 * Not thread-safe: client thread only.
 */
public final class PartnerMemory {

    /** Where we saw an item. */
    public enum Source { TRADE, CHAT }

    public record Seen(ItemPrint print, Long value, long at, Source source) {
    }

    /** A look-alike that was shown before and is better than what is offered now. */
    public record Match(Seen shown, TradeDiff.Result diff, long ageMs, Double valueDropPct) {
    }

    /** Value drop that counts as "worse" when ids and attributes match. */
    public static final double VALUE_DROP = 0.10;
    private static final int PER_PARTNER = 64;

    private final Map<String, Deque<Seen>> byPartner = new HashMap<>();

    public void record(String partner, ItemPrint print, Long value, long now, Source source) {
        if (partner == null || print == null || print.isCoins()) {
            return;
        }
        Deque<Seen> list = byPartner.computeIfAbsent(key(partner), k -> new ArrayDeque<>());
        // Same item seen again: refresh, keep the best value we ever had for it.
        for (Iterator<Seen> it = list.iterator(); it.hasNext(); ) {
            Seen s = it.next();
            if (s.print().identity().equals(print.identity()) && s.print().sameAs(print)) {
                value = best(s.value(), value);
                it.remove();
            }
        }
        list.addFirst(new Seen(print, value, now, source));
        while (list.size() > PER_PARTNER) {
            list.removeLast();
        }
    }

    /**
     * The best earlier look-alike from this partner that beats {@code offered}, or {@code null}.
     * Items in {@code onTable} (offered right now) are ignored: two real drills in one trade are
     * two items, not a switch.
     */
    public Match find(String partner, ItemPrint offered, Long offeredValue, long now, long retentionMs,
                      Collection<ItemPrint> onTable) {
        if (partner == null || offered == null || offered.isCoins()) {
            return null;
        }
        Deque<Seen> list = byPartner.get(key(partner));
        if (list == null) {
            return null;
        }
        list.removeIf(s -> now - s.at() > retentionMs);
        String family = ItemFamily.of(offered);
        Match best = null;
        for (Seen s : list) {
            ItemPrint old = s.print();
            if (old.identity().equals(offered.identity()) || old.sameAs(offered)) {
                continue;   // the very item shown before
            }
            if (onTable != null && onTable.stream().anyMatch(t -> t.identity().equals(old.identity()))) {
                continue;
            }
            if (!family.equals(ItemFamily.of(old)) && !ItemFamily.lookAlike(old, offered)) {
                continue;
            }
            TradeDiff.Result diff = TradeDiff.compare(old, offered);
            Double drop = null;
            if (s.value() != null && offeredValue != null && s.value() > 0) {
                drop = (s.value() - offeredValue) / (double) s.value();
            }
            boolean otherId = !Objects.equals(old.skyblockId(), offered.skyblockId());
            boolean cheaper = drop != null && drop > VALUE_DROP;
            boolean dearer = drop != null && drop < -VALUE_DROP;
            if (dearer || !(otherId || cheaper || diff.worse())) {
                continue;   // an upgrade or a sideways change is no scam
            }
            Match m = new Match(s, diff, now - s.at(), drop);
            if (best == null || rank(m) > rank(best)) {
                best = m;
            }
        }
        return best;
    }

    public void clear() {
        byPartner.clear();
    }

    public int size() {
        return byPartner.values().stream().mapToInt(Deque::size).sum();
    }

    private static double rank(Match m) {
        return (m.valueDropPct() == null ? 0 : m.valueDropPct()) * 1000 + m.diff().lines().size();
    }

    private static Long best(Long a, Long b) {
        if (a == null) {
            return b;
        }
        return b == null ? a : Math.max(a, b);
    }

    private static String key(String partner) {
        return partner.toLowerCase(Locale.ROOT);
    }
}
