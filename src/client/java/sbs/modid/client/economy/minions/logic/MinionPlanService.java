/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.minions.logic;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.economy.bazaar.logic.LocalFlipEngine;
import sbs.modid.client.economy.bazaar.model.BazaarSnapshot;
import sbs.modid.client.economy.minions.model.MinionData;
import sbs.modid.client.economy.minions.model.MinionModifierData;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs the {@link MinionOptimizer} off-thread: one solve per request, progress published for the
 * screen, the finished {@link MinionOptimizer.Result} swapped in atomically. The optimizer only
 * recommends - nothing here (or anywhere downstream) buys, crafts or places anything.
 */
public final class MinionPlanService {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SBS-MinionPlan");
        thread.setDaemon(true);
        return thread;
    });

    private static volatile MinionOptimizer.Result result;
    private static volatile boolean solving;
    private static volatile String error;
    private static volatile String stage = "";
    private static volatile int progressPct;

    private MinionPlanService() {
    }

    public static MinionOptimizer.Result result() {
        return result;
    }

    public static boolean solving() {
        return solving;
    }

    public static String error() {
        return error;
    }

    public static String stage() {
        return stage;
    }

    public static int progressPct() {
        return progressPct;
    }

    /** The slot count the plan will fill: explicit override, else what has been witnessed. */
    public static int effectiveSlots() {
        SBSConfig.MinionCalcSettings cfg = ConfigManager.getInstance().get().minionCalc;
        if (cfg.planSlots > 0) {
            return cfg.planSlots;
        }
        MinionStateStore store = MinionStateStore.getInstance();
        if (store.minionsLimit() > 0) {
            return store.minionsLimit();
        }
        MinionModifierData modifiers = MinionCatalogs.modifiers();
        if (modifiers != null && store.craftedReadAt() > 0) {
            return modifiers.slotsFor(store.uniqueCraftCount(), cfg.communitySlots);
        }
        return modifiers == null ? 5 : modifiers.baseSlots;
    }

    /** Kicks off a solve with the current config; a solve already running wins. */
    public static void solve() {
        if (solving) {
            return;
        }
        solving = true;
        error = null;
        stage = "Starting";
        progressPct = 0;
        EXECUTOR.execute(MinionPlanService::run);
    }

    private static void run() {
        try {
            MinionData data = MinionCatalogs.minions();
            MinionModifierData modifiers = MinionCatalogs.modifiers();
            if (data == null || modifiers == null) {
                error = "Minion catalog not loaded";
                return;
            }
            // The same shared pull the calculator uses; keeps every price this solve reads fresh.
            BazaarSnapshot.getInstance().get(60_000L);

            SBSConfig.MinionCalcSettings cfg = ConfigManager.getInstance().get().minionCalc;
            double tax = LocalFlipEngine.taxRate(
                    ConfigManager.getInstance().get().bazaar.bazaarFlipperLevel);
            MinionOptimizer.Objective objective = switch (cfg.planObjective) {
                case 1 -> MinionOptimizer.Objective.XP;
                case 2 -> MinionOptimizer.Objective.BLEND;
                default -> MinionOptimizer.Objective.COINS;
            };
            MinionOptimizer.Params params = new MinionOptimizer.Params(
                    Math.max(0, cfg.planBudget), effectiveSlots(), objective, cfg.rankSkill,
                    Math.max(1, cfg.planHorizonDays), Math.max(0, cfg.planMaxPerType),
                    cfg.planUseOwned, Math.max(1, cfg.intervalHours), tax,
                    1.0 + cfg.xpBoostPct / 100.0,
                    cfg.extraSpeedPct + MinionCalcService.permanentSpeedPct(modifiers, cfg));

            MinionStateStore store = MinionStateStore.getInstance();
            List<MinionOptimizer.Owned> owned = new ArrayList<>();
            for (MinionStateStore.Placed group : store.placed()) {
                MinionStateStore.Config config = store.configFor(group.type);
                owned.add(new MinionOptimizer.Owned(group.type, group.tier, group.count,
                        config == null ? "" : config.fuelId,
                        config == null ? List.of() : List.copyOf(config.upgradeIds),
                        config == null ? "" : config.hopperId));
            }

            result = MinionOptimizer.solve(data, modifiers, MinionPricesLive.getInstance(),
                    params, owned, store.craftedTiers(), (label, pct) -> {
                        stage = label;
                        progressPct = pct;
                    });
        } catch (Throwable t) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][MinionPlan] Solve failed", t);
            error = "Solve failed: " + t.getMessage();
        } finally {
            solving = false;
        }
    }
}
