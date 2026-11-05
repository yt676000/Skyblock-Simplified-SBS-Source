/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.kuudraprofit.logic;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import sbs.modid.client.combat.kuudraprofit.model.KuudraProfitData;
import sbs.modid.client.combat.kuudraprofit.model.KuudraProfitData.TierTotals;
import sbs.modid.client.combat.kuudraprofit.model.KuudraRun;
import sbs.modid.client.core.loot.RewardBlockReader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * The Kuudra Profit Tracker's books. Pure: time, tier and prices are passed in, so every rule below
 * is unit tested without a game.
 *
 * <p><b>A run is booked when it ends.</b> {@code KUUDRA DOWN!} or {@code DEFEAT} stores the run with
 * its tier and duration. The chests are opened after the boss is down, so a reward block goes to the
 * newest won run of the same tier that ended within {@link #CHEST_WINDOW_MS}. A block nothing can take
 * (the end line was missed) opens a run of its own with no duration. A lost run has no chest, costs
 * no key and is booked at zero.
 *
 * <p><b>Income</b> is every item of the run's chests, priced; an item without a price is counted and
 * named, never zero, and the profit then ends in {@code +}. <b>Cost</b> is one key of the run's tier
 * per PAID chest: the price it was bought at when a purchase was seen (and the setting says so), else
 * its current price; a key with no price at all marks the cost unknown. The FREE chest costs nothing.
 */
public final class KuudraRunLedger {

    /** How long after a run ended its chests are still booked onto it. */
    static final long CHEST_WINDOW_MS = 10 * 60_000L;
    /** Unused keys remembered per tier; older ones fall off. */
    static final int MAX_KEYS = 64;

    private static final Gson GSON = new GsonBuilder().create();

    /** Prices and settings, read at booking time. */
    public interface Valuation {
        /** Unit price of an item by its chat name, or {@code null} when unknown. */
        Long unitPrice(String name);

        /** One key of this tier at today's price, or {@code null}. */
        Long keyPrice(int tier);

        /** Charge a paid chest the price its key was bought at (when the purchase was seen). */
        boolean keyAtPurchasePrice();
    }

    private KuudraProfitData data = new KuudraProfitData();
    private boolean readOnly;
    private long sessionStart;
    private int revision;

    public KuudraRunLedger(long sessionStart) {
        this.sessionStart = sessionStart;
    }

    // ------------------------------------------------------------------ read-out

    public KuudraProfitData data() {
        return data;
    }

    /** The newest run, or {@code null}. */
    public KuudraRun lastRun() {
        return data.runs.isEmpty() ? null : data.runs.get(data.runs.size() - 1);
    }

    public long sessionStart() {
        return sessionStart;
    }

    /** Bumped on every change; display models key on it. */
    public int revision() {
        return revision;
    }

    public boolean readOnly() {
        return readOnly;
    }

    /** Keys of this tier bought and not yet used. */
    public int keysHeld(int tier) {
        List<Long> keys = data.keysBought.get(String.valueOf(tier));
        return keys == null ? 0 : keys.size();
    }

    // ------------------------------------------------------------------ events

    /**
     * Books one event.
     *
     * @param tier       the run's tier as last read (0 = unknown)
     * @param durationMs the run's length so far, for a run end
     * @return the run this event changed, or {@code null}
     */
    public KuudraRun apply(KuudraLoot.Event event, int tier, long durationMs, long now, Valuation v) {
        KuudraRun changed = switch (event) {
            case KuudraLoot.RunEnded end -> runEnded(end.won(), tier, durationMs, now);
            case KuudraLoot.RewardBlock block -> rewardBlock(block, tier, now, v);
            case KuudraLoot.KeyBought key -> {
                keyBought(key, v);
                yield null;
            }
        };
        revision++;
        return changed;
    }

    private KuudraRun runEnded(boolean won, int tier, long durationMs, long now) {
        KuudraRun run = new KuudraRun();
        run.endMs = now;
        run.tier = tier;
        run.won = won;
        run.durationMs = Math.max(0L, durationMs);
        add(run);
        return run;
    }

    private void add(KuudraRun run) {
        data.runs.add(run);
        TierTotals totals = totals(run.tier);
        totals.runs++;
        if (run.won) {
            totals.won++;
        }
        totals.durationMs += run.durationMs;
        while (data.runs.size() > KuudraProfitData.MAX_RUNS) {
            data.runs.remove(0);
        }
    }

    private TierTotals totals(int tier) {
        return data.tiers.computeIfAbsent(KuudraProfitData.tierKey(tier), t -> new TierTotals());
    }

    private KuudraRun rewardBlock(KuudraLoot.RewardBlock block, int tier, long now, Valuation v) {
        KuudraRun run = chestTarget(tier, now);
        long before = run.profit();
        boolean incompleteBefore = run.incomplete();
        for (RewardBlockReader.Item item : block.items()) {
            book(run, item.name(), item.count(), v);
        }
        if (block.paid()) {
            run.paidChests++;
            Long cost = keyCost(run.tier, v);
            if (cost == null) {
                run.keyUnpriced = true;
            } else {
                run.keyCost += cost;
            }
        } else {
            run.freeChests++;
        }
        TierTotals totals = totals(run.tier);
        totals.profit += run.profit() - before;
        totals.best = Math.max(totals.best, run.profit());
        totals.incomplete |= run.incomplete() && !incompleteBefore;
        return run;
    }

    /** The won run this chest belongs to; see the class comment. */
    private KuudraRun chestTarget(int tier, long now) {
        KuudraRun last = lastRun();
        if (last != null && last.won && now - last.endMs <= CHEST_WINDOW_MS
                && (tier == 0 || last.tier == 0 || last.tier == tier)) {
            if (last.tier == 0 && tier != 0) {
                last.tier = tier;      // learned late; the totals stay under "T?"
            }
            return last;
        }
        KuudraRun loose = new KuudraRun();
        loose.endMs = now;
        loose.tier = tier;
        loose.won = true;
        add(loose);
        return loose;
    }

    /** One key of this tier: bought price when seen and wanted, else today's; {@code null} unknown. */
    private Long keyCost(int tier, Valuation v) {
        if (tier < 1) {
            return null;
        }
        List<Long> held = data.keysBought.get(String.valueOf(tier));
        Long bought = held == null || held.isEmpty() ? null : held.remove(0);
        if (bought != null && v.keyAtPurchasePrice()) {
            return bought;
        }
        return v.keyPrice(tier);
    }

    private void keyBought(KuudraLoot.KeyBought key, Valuation v) {
        if (key.tier() < 1) {
            return;
        }
        Long each = key.price() != null ? Long.valueOf(key.price() / Math.max(1, key.count())) : v.keyPrice(key.tier());
        List<Long> held = data.keysBought.computeIfAbsent(String.valueOf(key.tier()), t -> new ArrayList<>());
        for (int i = 0; i < key.count(); i++) {
            held.add(each);
        }
        while (held.size() > MAX_KEYS) {
            held.remove(0);
        }
    }

    private static void book(KuudraRun run, String name, int count, Valuation v) {
        Long unit = v.unitPrice(name);
        if (unit == null) {
            run.unpriced++;
            if (run.unpricedNames.size() < KuudraRun.MAX_NAMES && !run.unpricedNames.contains(name)) {
                run.unpricedNames.add(name);
            }
            return;
        }
        long value = unit * Math.max(1, count);
        run.income += value;
        if (value > run.topDropValue) {
            run.topDropValue = value;
            run.topDrop = count > 1 ? name + " x" + count : name;
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /** Session reset: totals from now on. Stored runs are kept. */
    public void resetSession(long now) {
        sessionStart = now;
        revision++;
    }

    /** History, totals and remembered keys of this profile. */
    public void resetAll() {
        data = new KuudraProfitData();
        revision++;
    }

    // ------------------------------------------------------------------ persistence

    public String toJson() {
        return GSON.toJson(data);
    }

    /** Loads a stored file; returns why it was not used, or {@code null}. Null/blank starts empty. */
    public String load(String json) {
        readOnly = false;
        data = new KuudraProfitData();
        revision++;
        if (json == null || json.isBlank()) {
            return null;
        }
        KuudraProfitData parsed;
        try {
            parsed = GSON.fromJson(json, KuudraProfitData.class);
        } catch (JsonParseException e) {
            return "unreadable: " + e.getMessage();
        }
        if (parsed == null) {
            return null;
        }
        if (parsed.schemaVersion > KuudraProfitData.SCHEMA_VERSION) {
            readOnly = true;
            return "written by a newer build (schema " + parsed.schemaVersion + ")";
        }
        if (parsed.runs == null) {
            parsed.runs = new ArrayList<>();
        }
        if (parsed.tiers == null) {
            parsed.tiers = new LinkedHashMap<>();
        }
        if (parsed.keysBought == null) {
            parsed.keysBought = new LinkedHashMap<>();
        }
        parsed.runs.removeIf(r -> r == null);
        for (KuudraRun run : parsed.runs) {
            if (run.unpricedNames == null) {
                run.unpricedNames = new ArrayList<>();
            }
            if (run.topDrop == null) {
                run.topDrop = "";
            }
        }
        parsed.schemaVersion = KuudraProfitData.SCHEMA_VERSION;
        data = parsed;
        return null;
    }
}
