/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.profit.logic;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import sbs.modid.client.dungeons.profit.logic.DungeonLoot.ChestKind;
import sbs.modid.client.dungeons.profit.logic.DungeonLoot.Event;
import sbs.modid.client.dungeons.profit.model.DungeonProfitData;
import sbs.modid.client.dungeons.profit.model.DungeonProfitData.FloorTotals;
import sbs.modid.client.dungeons.profit.model.DungeonRun;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

/**
 * The Dungeon Profit Tracker's books. Pure: time and prices are passed in, so every rule below is
 * unit tested without a game.
 *
 * <p><b>Where a booking goes.</b> Anything while a run is open goes to that run. A reward block with
 * no run open is a Croesus claim: it goes to the newest finished run of the floor the Croesus page
 * named (when one was open in the last two minutes) that has not had a chest claimed yet, else the
 * newest of that floor, else the newest unclaimed run of any floor. A claim no stored run can take
 * is booked as a {@link DungeonRun#claimOnly} entry - coins counted, never a run. A Kismet Feather
 * used with no run open (the reroll in a Croesus chest menu) waits for the next claim, which is the
 * chest it rerolled and so goes to the same older run; one no claim follows within
 * {@link #KISMET_PENDING_MS} is dropped rather than charged to some later, unrelated claim.
 *
 * <p><b>Costs.</b> A run's cost is four lines kept apart so each can be shown: chest prices, Kismet
 * Feathers, Dungeon Potions drunk before the start ({@link #potionConsumed}) and the player's own
 * extra cost. The per-floor totals keep the same four.
 *
 * <p><b>Unpriced is never zero.</b> An item without a price is counted and named, and the run's
 * profit is then a floor ({@link DungeonRun#incomplete()}); a paid chest whose price was never seen
 * likewise marks the cost as unknown.
 *
 * <p><b>No double counting.</b> The reward block lists the whole chest; the "unlocked" and
 * "RARE REWARD!" broadcasts repeat some of it. A broadcast item is booked only when no block in the
 * last {@link #BROADCAST_WINDOW_MS} listed it.
 */
public final class RunLedger {

    static final long BROADCAST_WINDOW_MS = 30_000L;
    static final long CROESUS_FLOOR_FRESH_MS = 120_000L;
    /** A reroll is claimed seconds later; a feather waiting longer than this has no claim coming. */
    static final long KISMET_PENDING_MS = 300_000L;

    private static final Gson GSON = new GsonBuilder().create();
    private static final java.util.regex.Pattern WHITESPACE = java.util.regex.Pattern.compile("\\s+");

    /** Prices and settings, read at booking time. */
    public interface Valuation {
        /** Unit price of an item by its chat name, or {@code null} when unknown. */
        Long unitPrice(String name);

        /** One Kismet Feather, or {@code null}. */
        Long kismetPrice();

        /** What this chest tier cost to open, as last seen in its menu, or {@code null}. */
        Long chestCost(ChestKind kind);

        /** One Dungeon Potion by its {@link PotionWatch#idOf id}, per the potion cost setting, or {@code null}. */
        Long potionPrice(String id);

        long userCostPerRun();

        boolean countEveryoneEssence();
    }

    private DungeonProfitData data = new DungeonProfitData();
    private boolean readOnly;
    private long sessionStart;
    private int pendingKismets;
    private long pendingKismetAt;
    private String croesusFloor;
    private long croesusFloorAt;
    private final List<DungeonLoot.Item> recentBlock = new ArrayList<>();
    private long recentBlockAt = Long.MIN_VALUE / 2;
    private DungeonRun recentBlockRun;
    private Consumer<String> rareSink = name -> { };
    private int revision;

    public RunLedger(long sessionStart) {
        this.sessionStart = sessionStart;
    }

    /** Called with each own rare drop's name as it is booked (the lifetime tally). */
    public void onRare(Consumer<String> sink) {
        this.rareSink = sink == null ? name -> { } : sink;
    }

    // ------------------------------------------------------------------ read-out

    public DungeonProfitData data() {
        return data;
    }

    public DungeonRun current() {
        return data.current;
    }

    /** The newest finished real run, or {@code null}. */
    public DungeonRun lastRun() {
        for (int i = data.runs.size() - 1; i >= 0; i--) {
            if (!data.runs.get(i).claimOnly) {
                return data.runs.get(i);
            }
        }
        return null;
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

    // ------------------------------------------------------------------ lifecycle

    /** A dungeon was entered on {@code floor}. A run still open is closed first. */
    public void startRun(String floor, long now) {
        if (data.current != null) {
            endRun(now);
        }
        DungeonRun run = new DungeonRun();
        run.startMs = now;
        run.floor = floor == null || floor.isEmpty() ? "?" : floor;
        data.current = run;
        revision++;
    }

    /** The scoreboard learned the floor after the run opened. */
    public void floorKnown(String floor) {
        if (data.current != null && "?".equals(data.current.floor) && floor != null && !"?".equals(floor)) {
            data.current.floor = floor;
            revision++;
        }
    }

    /**
     * The dungeon was left. Returns the finished run, or {@code null} when nothing was booked (an
     * entry and exit with no summary and no loot is not a run).
     */
    public DungeonRun endRun(long now, long userCost) {
        DungeonRun run = data.current;
        data.current = null;
        if (run == null) {
            return null;
        }
        revision++;
        if (run.empty()) {
            return null;
        }
        run.endMs = Math.max(now, run.startMs + 1);
        run.userCost = Math.max(0L, userCost);
        data.runs.add(run);
        FloorTotals totals = data.floors.computeIfAbsent(run.floor, f -> new FloorTotals());
        totals.runs++;
        totals.profit += run.profit();
        totals.durationMs += run.durationMs();
        totals.best = Math.max(totals.best, run.profit());
        totals.rareDrops += run.rareDrops.size();
        totals.incomplete |= run.incomplete();
        totals.chestCost += run.chestCost;
        totals.kismets += run.kismets;
        totals.kismetCost += run.kismetCost;
        totals.potions += run.potions;
        totals.potionCost += run.potionCost;
        totals.userCost += run.userCost;
        prune();
        return run;
    }

    private DungeonRun endRun(long now) {
        return endRun(now, 0L);
    }

    /** A Croesus run page ({@code Catacombs - Floor IV}) is open; its floor targets the next claim. */
    public void croesusPage(String floor, long now) {
        croesusFloor = floor;
        croesusFloorAt = now;
    }

    /** Session reset: totals from now on. Stored runs are kept. */
    public void resetSession(long now) {
        sessionStart = now;
        revision++;
    }

    /** History and totals of this profile. */
    public void resetAll() {
        data = new DungeonProfitData();
        pendingKismets = 0;
        recentBlock.clear();
        revision++;
    }

    // ------------------------------------------------------------------ events

    /** Books one event. Returns whether anything changed. */
    public boolean apply(Event event, long now, Valuation v) {
        boolean changed = switch (event) {
            case DungeonLoot.RewardBlock block -> rewardBlock(block, now, v);
            case DungeonLoot.Unlocked unlocked -> unlocked(unlocked, now, v);
            case DungeonLoot.EssenceFound essence -> essence(essence, v);
            case DungeonLoot.RareReward rare -> rare(rare, now, v);
            case DungeonLoot.KismetUsed k -> kismet(now, v);
            case DungeonLoot.TeamScore score -> score(score);
            // The tracker's: a purchase price to remember, the end of the potion window.
            case DungeonLoot.PotionBought bought -> false;
            case DungeonLoot.PotionsLocked locked -> false;
        };
        if (changed) {
            revision++;
        }
        return changed;
    }

    private boolean rewardBlock(DungeonLoot.RewardBlock block, long now, Valuation v) {
        DungeonRun run = data.current;
        boolean claim = run == null;
        long before = 0;
        long chestBefore = 0;
        int kismetsBefore = 0;
        long kismetBefore = 0;
        if (claim) {
            run = croesusTarget(now);
            before = run.profit();
            chestBefore = run.chestCost;
            kismetsBefore = run.kismets;
            kismetBefore = run.kismetCost;
            run.croesusClaims++;
        }
        int rareBefore = run.rareDrops.size();
        boolean incompleteBefore = run.incomplete();
        recentBlock.clear();
        recentBlock.addAll(block.items());
        recentBlockAt = now;
        recentBlockRun = run;
        for (DungeonLoot.Item item : block.items()) {
            book(run, item.name(), item.count(), item.rare(), v);
        }
        run.chests++;
        if (block.kind() != ChestKind.WOOD) {
            Long cost = v.chestCost(block.kind());
            if (cost == null) {
                run.costUnknown = true;
            } else {
                run.chestCost += cost;
            }
        }
        if (claim) {
            if (now - pendingKismetAt <= KISMET_PENDING_MS) {
                while (pendingKismets > 0) {
                    pendingKismets--;
                    chargeKismet(run, v);
                }
            }
            pendingKismets = 0;
            if (run.finished() && !run.claimOnly) {
                FloorTotals totals = data.floors.computeIfAbsent(run.floor, f -> new FloorTotals());
                totals.profit += run.profit() - before;
                totals.best = Math.max(totals.best, run.profit());
                totals.rareDrops += run.rareDrops.size() - rareBefore;
                totals.incomplete |= run.incomplete() && !incompleteBefore;
                totals.chestCost += run.chestCost - chestBefore;
                totals.kismets += run.kismets - kismetsBefore;
                totals.kismetCost += run.kismetCost - kismetBefore;
            }
        }
        return true;
    }

    /** The run a Croesus claim belongs to; see the class comment. */
    private DungeonRun croesusTarget(long now) {
        String floor = croesusFloor != null && now - croesusFloorAt <= CROESUS_FLOOR_FRESH_MS ? croesusFloor : null;
        DungeonRun sameFloor = null;
        DungeonRun anyUnclaimed = null;
        for (int i = data.runs.size() - 1; i >= 0; i--) {
            DungeonRun run = data.runs.get(i);
            if (run.claimOnly) {
                continue;
            }
            boolean unclaimed = run.chests == 0;
            if (floor != null && floor.equals(run.floor)) {
                if (unclaimed) {
                    return run;
                }
                if (sameFloor == null) {
                    sameFloor = run;
                }
            } else if (floor == null && unclaimed && anyUnclaimed == null) {
                anyUnclaimed = run;
            }
        }
        if (sameFloor != null) {
            return sameFloor;
        }
        if (anyUnclaimed != null) {
            return anyUnclaimed;
        }
        DungeonRun loose = new DungeonRun();
        loose.claimOnly = true;
        loose.floor = floor == null ? "?" : floor;
        loose.startMs = now;
        loose.endMs = now;
        data.runs.add(loose);
        prune();
        return loose;
    }

    private boolean unlocked(DungeonLoot.Unlocked unlocked, long now, Valuation v) {
        DungeonRun run = broadcastTarget(now);
        if (!unlocked.own() || run == null || listedRecently(unlocked.item(), unlocked.count(), now)) {
            return false;
        }
        book(run, unlocked.item(), unlocked.count(), false, v);
        return true;
    }

    private boolean rare(DungeonLoot.RareReward rare, long now, Valuation v) {
        DungeonRun run = broadcastTarget(now);
        if (!rare.own() || run == null || listedRecently(rare.item(), 1, now)) {
            return false;
        }
        book(run, rare.item(), 1, true, v);
        return true;
    }

    /**
     * The run a broadcast belongs to: the open run, else - after the run, or at Croesus - the run
     * the last reward block went to while that block is recent. No block, no target: a broadcast
     * alone cannot say which run it was.
     */
    private DungeonRun broadcastTarget(long now) {
        if (data.current != null) {
            return data.current;
        }
        if (now - recentBlockAt > BROADCAST_WINDOW_MS || !data.runs.contains(recentBlockRun)) {
            return null;
        }
        return recentBlockRun;
    }

    /** Consumes a matching item of the last block, so each listed item excuses one broadcast. */
    private boolean listedRecently(String name, int count, long now) {
        if (now - recentBlockAt > BROADCAST_WINDOW_MS) {
            return false;
        }
        for (Iterator<DungeonLoot.Item> it = recentBlock.iterator(); it.hasNext(); ) {
            DungeonLoot.Item item = it.next();
            if (sameName(item.name(), name) && item.count() == count) {
                it.remove();
                return true;
            }
        }
        return false;
    }

    /** Case and runs of whitespace aside, the same item name (block and broadcast may differ). */
    static boolean sameName(String a, String b) {
        return WHITESPACE.matcher(a.trim()).replaceAll(" ").equalsIgnoreCase(WHITESPACE.matcher(b.trim()).replaceAll(" "));
    }

    private boolean essence(DungeonLoot.EssenceFound essence, Valuation v) {
        DungeonRun run = data.current;
        if (run == null) {
            return false;
        }
        if (essence.everyone()) {
            if (!v.countEveryoneEssence()) {
                return false;
            }
        } else if (!essence.own()) {
            return false;
        }
        book(run, essence.type() + " Essence", essence.count(), false, v);
        return true;
    }

    private boolean kismet(long now, Valuation v) {
        if (data.current == null) {
            if (now - pendingKismetAt > KISMET_PENDING_MS) {
                pendingKismets = 0;
            }
            pendingKismets++;
            pendingKismetAt = now;
            return false;
        }
        chargeKismet(data.current, v);
        return true;
    }

    /**
     * A Dungeon Potion was drunk before the start line ({@link PotionWatch}). Books it on the open
     * run; returns {@code false} when none is open.
     */
    public boolean potionConsumed(String id, Valuation v) {
        DungeonRun run = data.current;
        if (run == null || id == null) {
            return false;
        }
        run.potions++;
        Long price = v.potionPrice(id);
        if (price == null) {
            run.potionUnpriced = true;
        } else {
            run.potionCost += price;
        }
        revision++;
        return true;
    }

    private static void chargeKismet(DungeonRun run, Valuation v) {
        run.kismets++;
        Long price = v.kismetPrice();
        if (price == null) {
            run.kismetUnpriced = true;
        } else {
            run.kismetCost += price;
        }
    }

    private boolean score(DungeonLoot.TeamScore score) {
        if (data.current == null) {
            return false;
        }
        data.current.score = score.score();
        data.current.grade = score.grade();
        return true;
    }

    private void book(DungeonRun run, String name, int count, boolean rare, Valuation v) {
        Long unit = v.unitPrice(name);
        if (unit == null) {
            run.unpriced++;
            if (run.unpricedNames.size() < DungeonRun.MAX_NAMES && !run.unpricedNames.contains(name)) {
                run.unpricedNames.add(name);
            }
        } else {
            long value = unit * Math.max(1, count);
            run.income += value;
            if (value > run.topDropValue) {
                run.topDropValue = value;
                run.topDrop = count > 1 ? name + " x" + count : name;
            }
        }
        if (rare) {
            run.rareDrops.add(name);
            rareSink.accept(name);
        }
    }

    private void prune() {
        while (data.runs.size() > DungeonProfitData.MAX_RUNS) {
            data.runs.remove(0);
        }
    }

    // ------------------------------------------------------------------ persistence

    public String toJson() {
        return GSON.toJson(data);
    }

    /** Loads a stored file; returns why it was not used, or {@code null}. Null/blank starts empty. */
    public String load(String json) {
        readOnly = false;
        data = new DungeonProfitData();
        recentBlock.clear();
        pendingKismets = 0;
        revision++;
        if (json == null || json.isBlank()) {
            return null;
        }
        DungeonProfitData parsed;
        try {
            parsed = GSON.fromJson(json, DungeonProfitData.class);
        } catch (JsonParseException e) {
            return "unreadable: " + e.getMessage();
        }
        if (parsed == null) {
            return null;
        }
        if (parsed.schemaVersion > DungeonProfitData.SCHEMA_VERSION) {
            readOnly = true;
            return "written by a newer build (schema " + parsed.schemaVersion + ")";
        }
        if (parsed.runs == null) {
            parsed.runs = new ArrayList<>();
        }
        if (parsed.floors == null) {
            parsed.floors = new java.util.LinkedHashMap<>();
        }
        parsed.runs.removeIf(r -> r == null);
        for (DungeonRun run : parsed.runs) {
            if (run.unpricedNames == null) {
                run.unpricedNames = new ArrayList<>();
            }
            if (run.rareDrops == null) {
                run.rareDrops = new ArrayList<>();
            }
            if (run.floor == null) {
                run.floor = "?";
            }
        }
        parsed.schemaVersion = DungeonProfitData.SCHEMA_VERSION;
        data = parsed;
        return null;
    }
}
