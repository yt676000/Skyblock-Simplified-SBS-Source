/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.nucleus.logic;

import sbs.modid.client.skills.mining.nucleus.model.NucleusRunData.Line;
import sbs.modid.client.skills.mining.nucleus.model.NucleusRunData.Run;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.ToLongFunction;

/**
 * What a run is worth. Pure - a run and a price source in, figures out.
 *
 * <p>Two buckets of revenue and one of costs: <i>Nucleus profit</i> is the bundle less the costs,
 * <i>run profit</i> is the bundle plus the run loot less the costs. An item with no price counts 0 and
 * is listed in {@link Summary#unpriced()} - never a guessed price. A finished run carries its own
 * price snapshot on every line ({@link #finish}); after that only the snapshot is read, so a later
 * price move never changes a past run.
 */
public final class NucleusPricing {

    private NucleusPricing() {
    }

    /** Which Bazaar price an item is valued at. Stored by name on each finished run. */
    public enum Side {
        /** What selling right now pays: the highest buy order. */
        INSTANT_SELL,
        /** What a sell offer would ask: the lowest sell offer. */
        SELL_OFFER
    }

    /** A unit price for an item, or {@code null} when no market knows it. */
    @FunctionalInterface
    public interface PriceBook {
        Long unitPrice(String id, String name, Side side);
    }

    /** One drop for the summary's "most valuable" list. */
    public record Drop(String name, long qty, long value) {
    }

    public record Summary(long nucleusRevenue, long lootRevenue, long costs, long nucleusProfit,
                          long runProfit, List<String> unpriced, List<Drop> topDrops) {
    }

    /**
     * Writes the price snapshot into every line, settles self-obtained costs and stores the figures
     * on the run. Call once, when the run finishes.
     */
    public static Summary finish(Run run, PriceBook book, Side side, boolean selfObtainedCounted) {
        snapshot(run.nucleus, book, side);
        snapshot(run.loot, book, side);
        snapshot(run.costs, book, side);
        settleFree(run, selfObtainedCounted);
        run.priceSide = side.name();
        run.selfObtainedCounted = selfObtainedCounted;
        Summary summary = evaluate(run, line -> line.unitPrice);
        run.nucleusRevenue = summary.nucleusRevenue();
        run.lootRevenue = summary.lootRevenue();
        run.costTotal = summary.costs();
        run.nucleusProfit = summary.nucleusProfit();
        run.runProfit = summary.runProfit();
        return summary;
    }

    /** The figures of a finished run, from its snapshot. */
    public static Summary stored(Run run) {
        return evaluate(run, line -> line.unitPrice);
    }

    /**
     * The figures of an open run at today's prices, without changing it - what the card shows while
     * the run is in progress.
     */
    public static Summary preview(Run run, PriceBook book, Side side, boolean selfObtainedCounted) {
        Run copy = new Run();
        copy.nucleus = copyLines(run.nucleus);
        copy.loot = copyLines(run.loot);
        copy.costs = copyLines(run.costs);
        snapshot(copy.nucleus, book, side);
        snapshot(copy.loot, book, side);
        snapshot(copy.costs, book, side);
        settleFree(copy, selfObtainedCounted);
        return evaluate(copy, line -> line.unitPrice);
    }

    private static Summary evaluate(Run run, ToLongFunction<Line> unit) {
        Set<String> unpriced = new LinkedHashSet<>();
        List<Drop> drops = new ArrayList<>();
        long nucleus = sum(run.nucleus, unit, unpriced, drops, false);
        long loot = sum(run.loot, unit, unpriced, drops, false);
        long costs = sum(run.costs, unit, unpriced, null, true);
        drops.sort(Comparator.comparingLong(Drop::value).reversed());
        List<Drop> top = drops.subList(0, Math.min(3, drops.size()));
        return new Summary(nucleus, loot, costs, nucleus - costs, nucleus + loot - costs,
                List.copyOf(unpriced), List.copyOf(top));
    }

    private static long sum(List<Line> lines, ToLongFunction<Line> unit, Set<String> unpriced,
                            List<Drop> drops, boolean cost) {
        long total = 0L;
        for (Line line : lines) {
            long price = unit.applyAsLong(line);
            long qty = cost ? Math.max(0L, line.qty - line.free) : line.qty;
            if (price < 0) {
                if (qty > 0) {
                    unpriced.add(line.name != null ? line.name : line.id);
                }
                continue;
            }
            long value = price * qty;
            total += value;
            if (drops != null && value > 0) {
                drops.add(new Drop(line.name != null ? line.name : line.id, line.qty, value));
            }
        }
        return total;
    }

    private static void snapshot(List<Line> lines, PriceBook book, Side side) {
        for (Line line : lines) {
            Long price = line.id == null && line.name == null ? null : book.unitPrice(line.id, line.name, side);
            line.unitPrice = price != null && price > 0 ? price : -1L;
        }
    }

    /**
     * With self-obtained parts not counted, a cost is free up to what this run's loot booked of the
     * same item; two cost lines of one item share that allowance.
     */
    private static void settleFree(Run run, boolean selfObtainedCounted) {
        Map<String, Long> looted = new HashMap<>();
        if (!selfObtainedCounted) {
            for (Line line : run.loot) {
                looted.merge(itemKey(line), line.qty, Long::sum);
            }
        }
        for (Line cost : run.costs) {
            long available = looted.getOrDefault(itemKey(cost), 0L);
            long free = Math.min(available, cost.qty);
            cost.free = free;
            if (free > 0) {
                looted.put(itemKey(cost), available - free);
            }
        }
    }

    /** Lines of the same item merge on this: the id, or the lower-case name when unresolved. */
    public static String itemKey(Line line) {
        return line.id != null ? line.id : "name:" + (line.name == null ? "" : line.name.toLowerCase(Locale.ROOT));
    }

    private static List<Line> copyLines(List<Line> lines) {
        List<Line> copy = new ArrayList<>(lines.size());
        for (Line line : lines) {
            Line c = new Line(line.name, line.id, line.qty);
            c.rule = line.rule;
            c.estimated = line.estimated;
            copy.add(c);
        }
        return copy;
    }

    /** Merges {@code qty} of an item into a bucket. */
    public static Line add(List<Line> bucket, String name, String id, long qty) {
        Line probe = new Line(name, id, qty);
        String key = itemKey(probe);
        for (Line line : bucket) {
            if (itemKey(line).equals(key)) {
                line.qty += qty;
                if (line.name == null) {
                    line.name = name;
                }
                return line;
            }
        }
        bucket.add(probe);
        return probe;
    }
}
