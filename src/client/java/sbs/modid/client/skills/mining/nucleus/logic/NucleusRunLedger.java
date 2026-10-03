/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.nucleus.logic;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import sbs.modid.client.skills.mining.nucleus.model.Crystal;
import sbs.modid.client.skills.mining.nucleus.model.NucleusRunData;
import sbs.modid.client.skills.mining.nucleus.model.NucleusRunData.Line;
import sbs.modid.client.skills.mining.nucleus.model.NucleusRunData.Run;
import sbs.modid.client.skills.mining.nucleus.model.NucleusSignals;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The run lifecycle and the lifetime totals, for one profile. Pure - events and a clock in, state
 * out; the tracker owns the game, this owns the bookkeeping.
 *
 * <p><b>A run</b> opens the first time the player is on the Crystal Hollows with none open, and again
 * straight after each bundle. It ends at the bundle announcement - the only point where Hypixel lists
 * the loot (the pickup prints nothing; see the feature spec). Its duration is the time online on the
 * Crystal Hollows, accrued from {@link #tick} with each step capped, so a frozen client, a long load
 * or a night offline adds nothing. The open run is part of the saved file, so a restart, a relog or a
 * lobby change continues it.
 *
 * <p><b>Totals</b> are added to when a run finishes and are never recomputed from the history, so
 * pruning it to {@link #MAX_RUNS} leaves them unchanged.
 */
public final class NucleusRunLedger {

    public static final int MAX_RUNS = 500;
    /** The longest single step of online time a tick may add. */
    static final long MAX_TICK_STEP_MS = 1_000L;

    private static final Gson GSON = new GsonBuilder().create();

    private NucleusRunData data = new NucleusRunData();
    private long lastTickAt = -1L;
    /** Set when the saved file came from a newer build: nothing is written over it. */
    private boolean readOnly;

    public NucleusRunData data() {
        return data;
    }

    /** The run in progress, or {@code null}. */
    public Run current() {
        return data.current;
    }

    public boolean readOnly() {
        return readOnly;
    }

    /** Opens a run when none is open. */
    public Run ensureRun(long now) {
        if (data.current == null) {
            data.current = newRun(now);
        }
        return data.current;
    }

    /**
     * One client tick. On the Crystal Hollows a run is opened if needed and online time accrues;
     * anywhere else the clock stands still.
     *
     * @return whether the run's state changed in a way worth saving (a run was opened)
     */
    public boolean tick(long now, boolean onHollows) {
        if (!onHollows) {
            lastTickAt = -1L;
            return false;
        }
        boolean opened = data.current == null;
        Run run = ensureRun(now);
        if (lastTickAt >= 0 && now > lastTickAt) {
            run.onlineMs += Math.min(now - lastTickAt, MAX_TICK_STEP_MS);
        }
        lastTickAt = now;
        return opened;
    }

    /** World change or disconnect: the next tick starts a fresh step instead of bridging the gap. */
    public void pauseClock() {
        lastTickAt = -1L;
    }

    public void crystalFound(Crystal crystal, long now) {
        if (crystal == null) {
            return;
        }
        Run run = ensureRun(now);
        if (!Crystal.State.PLACED.name().equals(run.crystals.get(crystal.name()))) {
            run.crystals.put(crystal.name(), Crystal.State.FOUND.name());
        }
    }

    public void crystalPlaced(Crystal crystal, long now) {
        if (crystal != null) {
            ensureRun(now).crystals.put(crystal.name(), Crystal.State.PLACED.name());
        }
    }

    public Crystal.State crystalState(Crystal crystal) {
        Run run = data.current;
        if (run == null) {
            return Crystal.State.NONE;
        }
        String state = run.crystals.get(crystal.name());
        try {
            return state == null ? Crystal.State.NONE : Crystal.State.valueOf(state);
        } catch (IllegalArgumentException e) {
            return Crystal.State.NONE;
        }
    }

    /** A bundle item: the Nucleus bucket, and expected in a sack once the bundle is picked up. */
    public void bundleItem(String name, String id, long qty, long now) {
        NucleusPricing.add(ensureRun(now).nucleus, name, id, qty);
        SackGainFilter.expect(data.arrivals, name, qty, now + NucleusSignals.BUNDLE_ARRIVAL_MS);
    }

    /** A treasure chest item: run loot, and expected in a sack within a couple of minutes. */
    public void chestItem(String name, String id, long qty, long now) {
        NucleusPricing.add(ensureRun(now).loot, name, id, qty);
        SackGainFilter.expect(data.arrivals, name, qty, now + NucleusSignals.CHEST_ARRIVAL_MS);
    }

    /** A sack gain that {@link SackGainFilter} let through. */
    public void sackItem(String name, String id, long qty, long now) {
        NucleusPricing.add(ensureRun(now).loot, name, id, qty);
    }

    /** HotM XP or powder from either block. */
    public void nonCoin(String kind, long amount, long now) {
        ensureRun(now).nonCoin.merge(kind, amount, Long::sum);
    }

    public void cost(String ruleId, String name, String id, long qty, boolean estimated, long now) {
        if (qty <= 0) {
            return;
        }
        Line line = NucleusPricing.add(ensureRun(now).costs, name, id, qty);
        line.rule = line.rule == null ? ruleId : line.rule;
        line.estimated |= estimated;
    }

    /**
     * The bundle closed: price the run, store it, add it to the totals and open the next one.
     *
     * @return the finished run with its snapshot, and the summary for the chat message
     */
    public Finished finish(long now, NucleusPricing.PriceBook book, NucleusPricing.Side side,
                           boolean selfObtainedCounted) {
        Run run = ensureRun(now);
        long placed = run.crystals.values().stream()
                .filter(Crystal.State.PLACED.name()::equals).count();
        if (placed < Crystal.values().length) {
            run.partial = true;
        }
        run.endedAt = now;
        NucleusPricing.Summary summary = NucleusPricing.finish(run, book, side, selfObtainedCounted);

        NucleusRunData.Totals totals = data.totals;
        totals.runs++;
        totals.nucleusProfit += run.nucleusProfit;
        totals.runProfit += run.runProfit;
        totals.onlineMs += run.onlineMs;

        data.runs.add(run);
        while (data.runs.size() > MAX_RUNS) {
            data.runs.remove(0);
        }
        data.current = newRun(now);
        return new Finished(run, summary);
    }

    public record Finished(Run run, NucleusPricing.Summary summary) {
    }

    /** The last finished run, or {@code null}. */
    public Run lastRun() {
        return data.runs.isEmpty() ? null : data.runs.get(data.runs.size() - 1);
    }

    /** Expired arrivals off the ledger. */
    public void pruneArrivals(long now) {
        SackGainFilter.prune(data.arrivals, now);
    }

    /** History, totals, the open run and the arrivals ledger - everything, for this profile. */
    public void reset() {
        data = new NucleusRunData();
        lastTickAt = -1L;
    }

    // ------------------------------------------------------------------ persistence

    public String toJson() {
        return GSON.toJson(data);
    }

    /**
     * Replaces the state with a saved file's. A missing or unreadable file starts empty; a file from
     * a newer build is refused and left untouched on disk ({@link #readOnly()}).
     *
     * @return why the file was not used, or {@code null} when it was
     */
    public String load(String json) {
        data = new NucleusRunData();
        readOnly = false;
        lastTickAt = -1L;
        if (json == null || json.isBlank()) {
            return null;
        }
        NucleusRunData parsed;
        try {
            parsed = GSON.fromJson(json, NucleusRunData.class);
        } catch (JsonParseException e) {
            return "unreadable: " + e.getMessage();
        }
        if (parsed == null) {
            return null;
        }
        if (parsed.schemaVersion > NucleusRunData.SCHEMA_VERSION) {
            readOnly = true;
            return "written by a newer build (schema " + parsed.schemaVersion + ")";
        }
        data = repaired(parsed);
        return null;
    }

    /** Fills the collections an older or hand-edited file may lack, so no reader sees null. */
    private static NucleusRunData repaired(NucleusRunData parsed) {
        if (parsed.runs == null) {
            parsed.runs = new ArrayList<>();
        }
        parsed.runs.removeIf(run -> run == null);
        if (parsed.totals == null) {
            parsed.totals = new NucleusRunData.Totals();
        }
        if (parsed.arrivals == null) {
            parsed.arrivals = new ArrayList<>();
        }
        parsed.arrivals.removeIf(arrival -> arrival == null || arrival.key == null);
        for (Run run : parsed.runs) {
            repair(run);
        }
        if (parsed.current != null) {
            repair(parsed.current);
        }
        parsed.schemaVersion = NucleusRunData.SCHEMA_VERSION;
        return parsed;
    }

    private static void repair(Run run) {
        if (run.crystals == null) {
            run.crystals = new LinkedHashMap<>();
        }
        if (run.nucleus == null) {
            run.nucleus = new ArrayList<>();
        }
        if (run.loot == null) {
            run.loot = new ArrayList<>();
        }
        if (run.costs == null) {
            run.costs = new ArrayList<>();
        }
        if (run.nonCoin == null) {
            run.nonCoin = new LinkedHashMap<>();
        }
    }

    private static Run newRun(long now) {
        Run run = new Run();
        run.startedAt = now;
        Map<String, String> crystals = run.crystals;
        for (Crystal crystal : Crystal.values()) {
            crystals.put(crystal.name(), Crystal.State.NONE.name());
        }
        return run;
    }
}
