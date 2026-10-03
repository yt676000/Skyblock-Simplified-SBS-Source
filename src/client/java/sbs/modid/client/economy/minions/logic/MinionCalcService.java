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
import sbs.modid.client.economy.recipe.logic.RecipeCostResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Computes the minion rankings off-thread: one pass over the catalog through {@link MinionMath}
 * under the config's assumptions, published as an immutable {@link Result} the screen renders
 * lock-free. Never touches the client thread; never adds a network path - the one pull it makes
 * is the shared {@link BazaarSnapshot} every other bazaar consumer already collapses into.
 *
 * <p>While the screen is open, {@link #screenOpen} also widens the LBIN cache's refresh gate so
 * AH-only outputs get a price without the LBIN tooltip setting having to be on.
 */
public final class MinionCalcService {

    /** Recompute at most this often unless forced - prices underneath move on the same cadence. */
    private static final long FRESH_MS = 30_000L;

    /** How long a failed compute is left alone before the next automatic attempt. */
    private static final long RETRY_AFTER_FAILURE_MS = 15_000L;

    /**
     * One ranked minion at its evaluated (top) tier, with everything the row and tooltip show.
     * The {@code owned*} fields exist when the island scan has seen this type placed: the
     * projection at the tier the player actually runs, and the upgrade step FROM that tier -
     * the delta that matters, unlike the top step every player is far away from.
     */
    public record Row(MinionData.Minion minion, MinionData.Tier tier,
                      MinionMath.Projection projection, Long fromScratchCost, double paybackDays,
                      MinionMath.UpgradeDelta lastStep, double maxMarketShare,
                      int placedCount, int ownedTier, MinionMath.Projection ownedProjection,
                      MinionMath.UpgradeDelta ownedDelta, MinionMath.Assumptions assumptions) {
    }

    /** One published computation: rows plus the assumptions and timestamps they were made under. */
    public record Result(List<Row> rows, MinionMath.Assumptions assumptions, String fuelName,
                         double fuelCostPerDay, long computedAt, long bazaarDataAt) {
    }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SBS-MinionCalc");
        thread.setDaemon(true);
        return thread;
    });

    private static volatile Result result;
    private static volatile boolean computing;
    private static volatile String error;
    private static volatile long failedAt;

    /** Last time the calculator screen drew a frame; the LBIN gate's "is it open" signal. */
    private static volatile long screenSeenAt;

    private MinionCalcService() {
    }

    /** Called by the screen every rendered frame - no close hook needed, the signal just decays. */
    public static void touchScreen() {
        screenSeenAt = System.currentTimeMillis();
    }

    /** Whether the calculator wants AH prices right now (screen seen within the LBIN cycle). */
    public static boolean wantsAhPrices() {
        return System.currentTimeMillis() - screenSeenAt < 70_000L;
    }

    public static Result result() {
        return result;
    }

    public static boolean computing() {
        return computing;
    }

    public static String error() {
        return error;
    }

    /** Schedules a recompute unless a fresh one exists (or one is already running). */
    public static void request(boolean force) {
        Result current = result;
        long now = System.currentTimeMillis();
        if (computing || (!force && current != null && now - current.computedAt() < FRESH_MS)) {
            return;
        }
        // Failure backoff. Without it a failing compute publishes no result, so the freshness
        // guard above never bites and the screen's per-frame request re-runs (and re-logs) the
        // same failure thousands of times a minute - which is exactly what happened.
        if (!force && failedAt > 0 && now - failedAt < RETRY_AFTER_FAILURE_MS) {
            return;
        }
        computing = true;
        EXECUTOR.execute(() -> {
            try {
                result = compute();
                error = null;
                failedAt = 0;
            } catch (Throwable t) {
                SkyblockSimplifiedSBS.LOGGER.error("[SBS][MinionCalc] Compute failed", t);
                // The JVM strips the message (and stack) from a repeatedly thrown exception, so
                // an empty message is reported by class name rather than as a bare "null".
                String detail = t.getMessage() == null || t.getMessage().isBlank()
                        ? t.getClass().getSimpleName() + " (see log)" : t.getMessage();
                error = "Computation failed: " + detail;
                failedAt = System.currentTimeMillis();
            } finally {
                computing = false;
            }
        });
    }

    private static Result compute() {
        MinionData data = MinionCatalogs.minions();
        MinionModifierData modifiers = MinionCatalogs.modifiers();
        if (data == null || modifiers == null) {
            error = "Minion catalog not loaded";
            return result;
        }

        // One shared pull; the update wave hands it to every other bazaar consumer too.
        var snapshot = BazaarSnapshot.getInstance().get(60_000L);
        long bazaarDataAt = snapshot == null ? 0 : snapshot.lastUpdated;

        SBSConfig.MinionCalcSettings cfg = ConfigManager.getInstance().get().minionCalc;
        migrateCompactorFlag(cfg);
        double tax = LocalFlipEngine.taxRate(ConfigManager.getInstance().get().bazaar.bazaarFlipperLevel);

        MinionModifierData.Fuel fuel = cfg.fuelId.isEmpty() ? null : modifiers.fuel(cfg.fuelId);
        double fuelCostPerDay = fuel == null ? 0
                : MinionMath.fuelCostPerDay(fuel, RecipeCostResolver.getInstance().cost(fuel.id));
        // The permanent per-minion boosts occupy no slot, so they are a flat addition to the
        // stack rather than an upgrade choice - see permanentSpeedPct.
        double perkPct = permanentSpeedPct(modifiers, cfg);
        double speedPct = (fuel == null ? 0 : fuel.speedPct) + cfg.extraSpeedPct + perkPct;
        double outputMult = fuel == null ? 1.0 : fuel.outputMult;

        Double hopperCut = null;
        if (!cfg.hopperId.isEmpty()) {
            MinionModifierData.Hopper hopper = modifiers.hopper(cfg.hopperId);
            hopperCut = hopper == null ? null : hopper.cut;
        }

        MinionMath.Prices prices = MinionPricesLive.getInstance();
        MinionStateStore store = MinionStateStore.getInstance();
        List<MinionModifierData.Upgrade> chosenUpgrades = upgrades(modifiers, cfg);

        List<Row> rows = new ArrayList<>();
        for (MinionData.Minion minion : data.minions) {
            MinionData.Tier tier = topUsableTier(minion);
            if (tier == null) {
                continue;
            }
            // Per minion, because these depend on WHICH minion: a self-boosting type (Inferno) is
            // faster the more of it you run, and the best fuel + upgrades for a crop minion are
            // not the ones for a mob minion.
            int placedCount = store.placedCount(minion.type);
            int sameTypePlaced = rankedPlacedCount(minion, placedCount);

            MinionModifierData.Fuel rowFuel = fuel;
            double rowFuelCost = fuelCostPerDay;
            double rowSpeedPct = speedPct;
            double rowOutputMult = outputMult;
            List<MinionModifierData.Upgrade> fitted = chosenUpgrades;
            if (cfg.autoUpgrades) {
                MinionAutoSetup.Choice choice = MinionAutoSetup.best(minion, tier, data, modifiers,
                        prices, cfg, tax, cfg.extraSpeedPct + perkPct, hopperCut, sameTypePlaced);
                rowFuel = choice.fuel();
                rowFuelCost = choice.fuelCostPerDay();
                rowSpeedPct = cfg.extraSpeedPct + perkPct + (rowFuel == null ? 0 : rowFuel.speedPct);
                rowOutputMult = rowFuel == null ? 1.0 : rowFuel.outputMult;
                fitted = choice.upgrades();
            }
            MinionMath.Assumptions assumptions = new MinionMath.Assumptions(
                    Math.max(1, cfg.intervalHours), tax, rowSpeedPct, rowOutputMult, hopperCut,
                    fitted, 1.0 + cfg.xpBoostPct / 100.0, rowFuel, sameTypePlaced);
            final double fuelCostForRow = rowFuelCost;
            MinionMath.Projection projection =
                    MinionMath.project(minion, tier, data, modifiers, prices, assumptions, fuelCostForRow);
            Long fromScratch = MinionMath.fromScratchCost(minion, tier.tier, prices);
            double payback = fromScratch == null ? Double.POSITIVE_INFINITY
                    : MinionMath.paybackDays(fromScratch, projection.netCoinsPerDay());
            MinionMath.UpgradeDelta lastStep = tier.tier > 1
                    ? MinionMath.upgradeDelta(minion, tier.tier - 1, data, modifiers, prices,
                            assumptions, fuelCostForRow)
                    : null;
            double maxShare = 0;
            for (MinionMath.ValuedStream stream : projection.streams()) {
                maxShare = Math.max(maxShare, stream.marketShare());
            }
            // Witnessed ownership: what the island scan saw of this type, evaluated at the tier
            // the player actually runs so the upgrade advice starts from reality.
            int ownedTier = store.highestPlacedTier(minion.type);
            MinionMath.Projection ownedProjection = null;
            MinionMath.UpgradeDelta ownedDelta = null;
            if (ownedTier > 0) {
                MinionData.Tier owned = minion.tier(ownedTier);
                if (owned != null && owned.actionSeconds > 0) {
                    ownedProjection = MinionMath.project(minion, owned, data, modifiers, prices,
                            assumptions, fuelCostForRow);
                    ownedDelta = MinionMath.upgradeDelta(minion, ownedTier, data, modifiers, prices,
                            assumptions, fuelCostForRow);
                }
            }
            rows.add(new Row(minion, tier, projection, fromScratch, payback, lastStep, maxShare,
                    placedCount, ownedTier, ownedProjection, ownedDelta, assumptions));
        }
        // The header's assumptions: the configured ones. Each row carries the assumptions it was
        // actually evaluated under, which in auto-upgrade mode differ from minion to minion.
        MinionMath.Assumptions base = new MinionMath.Assumptions(
                Math.max(1, cfg.intervalHours), tax, speedPct, outputMult, hopperCut,
                chosenUpgrades, 1.0 + cfg.xpBoostPct / 100.0, fuel, 1);
        return new Result(List.copyOf(rows), base,
                fuel == null ? "none" : fuel.name, fuelCostPerDay,
                System.currentTimeMillis(), bazaarDataAt);
    }

    /**
     * The permanent per-minion boosts the player says every minion has: Mithril Infusion and a
     * surviving Free Will, +10% each from the modifier catalog. They take no upgrade slot, so they
     * belong in the additive stack next to crystals and the beacon, not in the slot pickers.
     */
    public static double permanentSpeedPct(MinionModifierData modifiers,
                                           SBSConfig.MinionCalcSettings cfg) {
        double total = 0;
        for (MinionModifierData.PermanentPerMinion perk : modifiers.permanentPerMinion) {
            boolean on = switch (perk.id) {
                case "MITHRIL_INFUSION" -> cfg.mithrilInfusion;
                case "FREE_WILL" -> cfg.freeWill;
                default -> false;
            };
            if (on) {
                total += perk.speedPct;
            }
        }
        return total;
    }

    /** What the two configured upgrade slots hold, unknown ids dropped. */
    public static List<MinionModifierData.Upgrade> upgrades(MinionModifierData modifiers,
                                                            SBSConfig.MinionCalcSettings cfg) {
        List<MinionModifierData.Upgrade> out = new ArrayList<>(2);
        for (String id : List.of(cfg.upgradeSlot1, cfg.upgradeSlot2)) {
            if (id != null && !id.isEmpty()) {
                MinionModifierData.Upgrade upgrade = modifiers.upgrade(id);
                if (upgrade != null) {
                    out.add(upgrade);
                }
            }
        }
        return out;
    }

    /**
     * One-time carry-over from the boolean this replaced. Absent means the config was written
     * since the change and the slots are authoritative; present means the player's old choice
     * still has to be honoured before the field is dropped.
     */
    @SuppressWarnings("deprecation")
    private static void migrateCompactorFlag(SBSConfig.MinionCalcSettings cfg) {
        if (cfg.superCompactor == null) {
            return;
        }
        if (!cfg.superCompactor && "SUPER_COMPACTOR_3000".equals(cfg.upgradeSlot1)) {
            cfg.upgradeSlot1 = "";
        }
        cfg.superCompactor = null;
        ConfigManager.getInstance().save();
    }

    /**
     * How many of this type the ranking assumes are placed.
     *
     * <p>For an ordinary minion this changes nothing, so it is simply 1. For a self-boosting type
     * it decides the answer: one Inferno Minion alone is a poor minion, ten together are the best
     * in the game, and since this list ranks value PER SLOT, the fair figure is the one each slot
     * yields in the setup that type is actually built for. The player's own count wins when the
     * island scan has seen more than that, and the tooltip always states which count was used.
     */
    private static int rankedPlacedCount(MinionData.Minion minion, int placedCount) {
        if (minion.perMinionPct <= 0) {
            return 1;
        }
        int cap = minion.maxPct > 0
                ? (int) Math.ceil(minion.maxPct / minion.perMinionPct) : 1;
        return Math.max(1, Math.max(placedCount, cap));
    }

    /** The highest tier with usable stats - the tier the ranking evaluates every minion at. */
    private static MinionData.Tier topUsableTier(MinionData.Minion minion) {
        MinionData.Tier best = null;
        for (MinionData.Tier tier : minion.tiers) {
            if (tier.actionSeconds > 0 && tier.storage > 0) {
                best = tier;
            }
        }
        return best;
    }
}
