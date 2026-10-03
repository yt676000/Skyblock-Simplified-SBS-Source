/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.minions.logic;

import sbs.modid.client.economy.minions.model.MinionData;
import sbs.modid.client.economy.minions.model.MinionModifierData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The budget/slot optimizer: given coins, slots and an objective, the best complete minion setup -
 * which minions, at which tiers, with which fuel, upgrades and hopper - solved as a bounded
 * knapsack rather than ranked greedily, because pure value-per-coin is wrong when slots bind and
 * pure value-per-slot is wrong when budget binds.
 *
 * <p><b>Cost accounting, stated once and applied consistently:</b> crafts, tier upgrades, upgrade
 * items, hoppers and PERMANENT fuels are upfront and come out of the budget; consumable fuel is a
 * drain on returns (already inside every net/day figure) and never a budget line. The beacon's
 * Power Crystal (one per 48h) is likewise a drain.
 *
 * <p><b>Structure.</b> Per minion type, candidate variants (tier x fuel x upgrade pair x hopper)
 * are evaluated through {@link MinionMath} and Pareto-pruned on (upfront, value). Owned minions
 * enter as their own groups with marginal upgrade costs and credit for the modifiers their GUIs
 * were seen carrying. An exact DP over (slots x bucketed budget) with a per-type copy cap picks
 * the setup; the island-wide beacon runs as an outer loop over its six tiers (that is what makes
 * "the beacon pays for itself at your minion count" answerable); family crystals are evaluated
 * against the finished setup and reported as advice rather than silently added.
 *
 * <p>Deliberate approximations, reported in {@link Result#notes}: variants are pruned at beacon 0
 * before the outer loop re-evaluates survivors; crystals are post-hoc advice; a Postcard is never
 * a purchase (it cannot be bought). Everything unpriceable is skipped and counted, never guessed.
 *
 * <p>Pure over its inputs - no Minecraft, no live caches - so the whole solver is testable
 * offline with fixed prices.
 */
public final class MinionOptimizer {

    public enum Objective { COINS, XP, BLEND }

    /** Progress callback for the async runner (stage label + rough percentage). */
    public interface ProgressSink {
        void progress(String stage, int pct);
    }

    /**
     * @param budget       upfront coins available
     * @param slots        minion slots to fill
     * @param objective    what "best" means; XP and BLEND read {@code xpSkill}
     * @param xpSkill      the skill XP objective ("MINING", ...)
     * @param horizonDays  evaluation horizon for advice lines (fuel/beacon worth-it calls)
     * @param maxPerType   diversity cap: max copies of one minion type; 0 = unlimited
     * @param useOwned     seed the plan with the minions the island scan saw placed
     * @param extraSpeedPct everything the island already gives every minion - crystals, Mithril
     *                      Infusion, Free Will, whatever the player states - excluding the beacon,
     *                      which this solver decides for itself
     */
    public record Params(long budget, int slots, Objective objective, String xpSkill,
                        double horizonDays, int maxPerType, boolean useOwned,
                        double collectionIntervalHours, double taxRate, double xpMult,
                        double extraSpeedPct) {
    }

    /** One owned minion group, as witnessed (config fields empty when never captured). */
    public record Owned(String type, int tier, int count, String fuelId,
                        List<String> upgradeIds, String hopperId) {
    }

    /**
     * One evaluated configuration of one minion type.
     *
     * <p>{@code slotsEach} is normally 1. It is larger for a <b>bundle</b>: a self-boosting type
     * (the Inferno Minion, whose every copy speeds up all the others) cannot be valued one copy at
     * a time, so k copies are evaluated together as a single indivisible choice worth k slots.
     */
    public record Variant(String type, String name, int targetTier, String fuelId,
                         List<String> upgradeIds, String hopperId, boolean fromOwned, int fromTier,
                         long upfront, double netCoinsPerDay, double xpPerDay, double value,
                         MinionMath.Projection projection, int slotsEach) {
    }

    /** One line of the answer: {@code count} copies of a variant. */
    public record Line(Variant variant, int count) {
    }

    /** Advice on one island-wide crystal, evaluated against the finished setup. */
    public record CrystalAdvice(String id, String name, long cost, double addedPerDay,
                               double paybackDays, boolean worthIt) {
    }

    public record Result(List<Line> setup, long spent, int slotsUsed,
                        double coinsPerDay, double xpPerDay, double paybackDays,
                        int beaconTier, long beaconUpfront, double beaconDrainPerDay,
                        String beaconNote, List<CrystalAdvice> crystals, int newUniqueTiers,
                        int skyblockXpFromCrafts, String sensitivity, List<String> notes,
                        long elapsedMs, int variantsConsidered, Params params) {
    }

    /** Budget resolution of the DP. 256 buckets keeps rounding under 0.4% of any budget. */
    private static final int BUCKETS = 256;

    /**
     * The two-slot combinations worth evaluating. Every id is validated against the modifier
     * catalog and against the minion itself at run time, so a pair the minion cannot take
     * (Corrupt Soil on a crop minion, Enchanted Egg outside the Chicken) simply never appears.
     *
     * <p>The only duplicate listed is the Minion Expander, which the wiki explicitly says stacks;
     * nothing else is assumed to.
     */
    private static final String[][] UPGRADE_PAIRS = {
            {},
            {"SUPER_COMPACTOR_3000"},
            {"SUPER_COMPACTOR_3000", "FLYCATCHER"},
            {"SUPER_COMPACTOR_3000", "DIAMOND_SPREADING"},
            {"SUPER_COMPACTOR_3000", "MINION_EXPANDER"},
            {"SUPER_COMPACTOR_3000", "CORRUPT_SOIL"},
            {"SUPER_COMPACTOR_3000", "ENCHANTED_EGG"},
            {"SUPER_COMPACTOR_3000", "ENCHANTED_SHEARS"},
            {"SUPER_COMPACTOR_3000", "BERBERIS_FUEL_INJECTOR"},
            {"SUPER_COMPACTOR_3000", "SOULFLOW_ENGINE"},
            {"DWARVEN_COMPACTOR"},
            {"DWARVEN_COMPACTOR", "FLYCATCHER"},
            {"DWARVEN_COMPACTOR", "CORRUPT_SOIL"},
            {"COMPACTOR"},
            {"COMPACTOR", "FLYCATCHER"},
            {"AUTO_SMELTER", "FLYCATCHER"},
            {"FLYCATCHER", "MINION_EXPANDER"},
            {"FLYCATCHER", "DIAMOND_SPREADING"},
            {"FLYCATCHER", "CORRUPT_SOIL"},
            {"MINION_EXPANDER", "MINION_EXPANDER"},
    };

    private static final String[] HOPPER_CHOICES = {"", "BUDGET_HOPPER", "ENCHANTED_HOPPER"};

    private MinionOptimizer() {
    }

    // ------------------------------------------------------------------ entry point

    public static Result solve(MinionData data, MinionModifierData mods, MinionMath.Prices prices,
                               Params params, List<Owned> owned, Set<String> craftedTiers,
                               ProgressSink progress) {
        long startedAt = System.currentTimeMillis();
        List<String> notes = new ArrayList<>();
        notes.add("Upfront: crafts, tier upgrades, upgrade items, permanent fuel. "
                + "Consumable fuel and beacon power are drains on returns, never budget lines.");

        // --- 1. candidate variants per group, Pareto-pruned at beacon 0 ---------------
        progress.progress("Evaluating configurations", 5);
        int[] considered = {0};
        List<Group> groups = buildGroups(data, mods, prices, params, owned, notes, considered);

        // --- 2. beacon outer loop over the pruned survivors ---------------------------
        Long powerCost = prices.acquisitionCost(mods.beacon.powerItem);
        double beaconDrainPerDay = powerCost == null ? 0
                : powerCost * 24.0 / mods.beacon.powerHours;
        if (powerCost == null) {
            notes.add("Power Crystal unpriceable - beacon evaluated without its running cost.");
        }

        Best best = null;
        double bestTotal = Double.NEGATIVE_INFINITY;
        double[] beaconTotals = new double[mods.beacon.maxTier + 1];
        for (int beaconTier = 0; beaconTier <= mods.beacon.maxTier; beaconTier++) {
            progress.progress("Solving (beacon " + beaconTier + ")",
                    20 + 70 * beaconTier / (mods.beacon.maxTier + 1));
            long beaconUpfront = 0;
            if (beaconTier > 0) {
                // Ids follow the live catalog: BEACON_1..BEACON_5.
                Long cost = prices.acquisitionCost("BEACON_" + beaconTier);
                if (cost == null) {
                    beaconTotals[beaconTier] = Double.NEGATIVE_INFINITY;
                    continue; // unpriceable tier - skipped, not guessed
                }
                beaconUpfront = cost;
            }
            long budget = params.budget() - beaconUpfront;
            if (budget < 0) {
                beaconTotals[beaconTier] = Double.NEGATIVE_INFINITY;
                continue;
            }
            double boost = beaconTier * mods.beacon.perTierPct;
            List<Group> boosted = beaconTier == 0 ? groups
                    : reevaluate(groups, data, mods, prices, params, params.extraSpeedPct() + boost, considered);
            Best candidate = runDp(boosted, budget, params);
            double drain = beaconTier > 0 ? beaconDrainPerDay : 0;
            double total = candidate.value - drainValue(drain, params);
            beaconTotals[beaconTier] = total;
            if (total > bestTotal) {
                bestTotal = total;
                candidate.beaconTier = beaconTier;
                candidate.beaconUpfront = beaconUpfront;
                candidate.beaconDrain = drain;
                best = candidate;
            }
        }
        if (best == null) {
            best = new Best(List.of(), 0, 0);
        }

        // --- 3. totals, advice, sensitivity -------------------------------------------
        progress.progress("Finishing", 95);
        double coinsPerDay = -best.beaconDrain;
        double xpPerDay = 0;
        long spent = best.beaconUpfront;
        int slotsUsed = 0;
        for (Line line : best.setup) {
            coinsPerDay += line.variant().netCoinsPerDay() * line.count();
            xpPerDay += line.variant().xpPerDay() * line.count();
            spent += line.variant().upfront() * line.count();
            slotsUsed += line.count();
        }
        String beaconNote = beaconNote(beaconTotals, best.beaconTier, slotsUsed);
        List<CrystalAdvice> crystals = crystalAdvice(best.setup, data, mods, prices, params);

        int newUniques = 0;
        int craftXp = 0;
        for (Line line : best.setup) {
            MinionData.Minion minion = byType(data, line.variant().type());
            if (minion == null) {
                continue;
            }
            int from = line.variant().fromOwned() ? line.variant().fromTier() : 0;
            for (MinionData.Tier tier : minion.tiers) {
                if (tier.tier > from && tier.tier <= line.variant().targetTier()
                        && !craftedTiers.contains(minion.type + "_" + tier.tier)
                        && !"drop".equals(tier.source)) {
                    newUniques++;
                    if (tier.tier < mods.skyblockXpPerTier.size()) {
                        craftXp += mods.skyblockXpPerTier.get(tier.tier);
                    }
                }
            }
        }

        String sensitivity = sensitivity(best.setup, groups);
        double payback = spent <= 0 ? 0
                : coinsPerDay <= 0 ? Double.POSITIVE_INFINITY : spent / coinsPerDay;
        progress.progress("Done", 100);
        return new Result(best.setup, spent, slotsUsed, coinsPerDay, xpPerDay, payback,
                best.beaconTier, best.beaconUpfront, best.beaconDrain, beaconNote, crystals,
                newUniques, craftXp, sensitivity, notes,
                System.currentTimeMillis() - startedAt, considered[0], params);
    }

    // ------------------------------------------------------------------ candidates

    /** One DP group: a minion type's new copies, or one owned block of it. */
    private static final class Group {
        final String type;
        final int cap;
        final boolean fromOwned;
        final int fromTier;
        final Owned ownedSource;
        List<Variant> variants = new ArrayList<>();

        Group(String type, int cap, boolean fromOwned, int fromTier, Owned ownedSource) {
            this.type = type;
            this.cap = cap;
            this.fromOwned = fromOwned;
            this.fromTier = fromTier;
            this.ownedSource = ownedSource;
        }
    }

    private static List<Group> buildGroups(MinionData data, MinionModifierData mods,
                                           MinionMath.Prices prices, Params params,
                                           List<Owned> owned, List<String> notes,
                                           int[] considered) {
        List<Group> groups = new ArrayList<>();
        int unpriceable = 0;
        for (MinionData.Minion minion : data.minions) {
            int cap = params.maxPerType() > 0 ? params.maxPerType() : params.slots();
            if (minion.perMinionPct > 0) {
                // Self-boosting type: one bundle group per copy count, each an all-or-nothing
                // choice. This is what lets the plan discover "ten Inferno Minions together beat
                // one" - a per-copy value could never show it, because the value IS the count.
                int maxCopies = Math.min(cap, Math.max(1, params.slots()));
                if (minion.maxPct > 0 && minion.perMinionPct > 0) {
                    maxCopies = Math.min(maxCopies,
                            (int) Math.ceil(minion.maxPct / minion.perMinionPct));
                }
                for (int copies = 1; copies <= maxCopies; copies++) {
                    Group bundle = new Group(minion.type, 1, false, 0, null);
                    for (MinionData.Tier tier : minion.tiers) {
                        if (tier.actionSeconds <= 0 || tier.storage <= 0) {
                            continue;
                        }
                        Long fromScratch = MinionMath.fromScratchCost(minion, tier.tier, prices);
                        if (fromScratch == null) {
                            continue;
                        }
                        addVariants(bundle, minion, tier, fromScratch, false, 0, null,
                                data, mods, prices, params, params.extraSpeedPct(), considered, copies);
                    }
                    prune(bundle);
                    if (!bundle.variants.isEmpty()) {
                        groups.add(bundle);
                    }
                }
                continue;
            }
            Group group = new Group(minion.type, cap, false, 0, null);
            for (MinionData.Tier tier : minion.tiers) {
                if (tier.actionSeconds <= 0 || tier.storage <= 0) {
                    continue;
                }
                Long fromScratch = MinionMath.fromScratchCost(minion, tier.tier, prices);
                if (fromScratch == null) {
                    unpriceable++;
                    continue; // drop-only line (Snow I) or unpriced tier: not buyable with coins
                }
                addVariants(group, minion, tier, fromScratch, false, 0, null,
                        data, mods, prices, params, params.extraSpeedPct(), considered, 1);
            }
            prune(group);
            if (!group.variants.isEmpty()) {
                groups.add(group);
            }
        }
        if (params.useOwned()) {
            for (Owned block : owned) {
                MinionData.Minion minion = byType(data, block.type());
                if (minion == null || block.count() <= 0) {
                    continue;
                }
                Group group = new Group(minion.type, block.count(), true, block.tier(), block);
                long marginal = 0;
                for (MinionData.Tier tier : minion.tiers) {
                    if (tier.tier < block.tier() || tier.actionSeconds <= 0 || tier.storage <= 0) {
                        continue;
                    }
                    if (tier.tier > block.tier()) {
                        Long step = MinionMath.marginalCost(tier, prices);
                        if (step == null) {
                            break; // cannot price the path further up - stop climbing
                        }
                        marginal += step;
                    }
                    addVariants(group, minion, tier, marginal, true, block.tier(), block,
                            data, mods, prices, params, params.extraSpeedPct(), considered,
                            Math.max(1, block.count()));
                }
                prune(group);
                if (!group.variants.isEmpty()) {
                    groups.add(group);
                }
            }
        }
        if (unpriceable > 0) {
            notes.add(unpriceable + " tier(s) skipped as unpriceable or not craftable with coins.");
        }
        return groups;
    }

    /** Evaluates every fuel x upgrade-pair x hopper for one tier and adds the priceable ones. */
    private static void addVariants(Group group, MinionData.Minion minion, MinionData.Tier tier,
                                    long baseCost, boolean fromOwned, int fromTier, Owned block,
                                    MinionData data, MinionModifierData mods,
                                    MinionMath.Prices prices, Params params, double extraSpeed,
                                    int[] considered, int copies) {
        List<String> fuels = new ArrayList<>();
        fuels.add("");
        for (MinionModifierData.Fuel fuel : mods.fuels) {
            // A fuel its minion rejects (the Inferno fuels anywhere else) is not a choice: it
            // would cost coins and do nothing.
            if (fuel.appliesTo(minion)) {
                fuels.add(fuel.id);
            }
        }
        for (String fuelId : fuels) {
            MinionModifierData.Fuel fuel = fuelId.isEmpty() ? null : mods.fuel(fuelId);
            Long fuelUpfront = 0L;
            double fuelDrain = 0;
            if (fuel != null) {
                Long unit = prices.acquisitionCost(fuel.id);
                if (unit == null) {
                    continue;
                }
                if (fuel.permanent) {
                    fuelUpfront = unit;
                } else {
                    fuelDrain = MinionMath.fuelCostPerDay(fuel, unit);
                }
            }
            for (String[] pair : UPGRADE_PAIRS) {
                double speed = (fuel == null ? 0 : fuel.speedPct) + extraSpeed;
                double outputMult = fuel == null ? 1.0 : fuel.outputMult;
                long upgradeCost = 0;
                boolean priceable = true;
                // The upgrades are handed to the engine whole - it owns every effect (speed,
                // compaction, smelting, added drops, spreading, engine cooldowns). Re-deriving
                // them here is what let entire upgrade kinds go unmodeled.
                List<MinionModifierData.Upgrade> fitted = new ArrayList<>(pair.length);
                for (String upgradeId : pair) {
                    MinionModifierData.Upgrade upgrade = mods.upgrade(upgradeId);
                    if (upgrade == null || !upgrade.appliesTo(minion)) {
                        priceable = false;
                        break;
                    }
                    Long cost = prices.acquisitionCost(upgradeId);
                    if (cost == null) {
                        priceable = false;
                        break;
                    }
                    if (fromOwned && block != null && block.upgradeIds().contains(upgradeId)) {
                        cost = 0L; // already sitting in the minion's GUI
                    }
                    upgradeCost += cost;
                    fitted.add(upgrade);
                }
                if (!priceable) {
                    continue;
                }
                if (fromOwned && block != null && fuel != null && fuel.permanent
                        && fuel.id.equals(block.fuelId())) {
                    fuelUpfront = 0L; // the captured GUI already holds this fuel
                }
                for (String hopperId : HOPPER_CHOICES) {
                    Double hopperCut = null;
                    long hopperCost = 0;
                    if (!hopperId.isEmpty()) {
                        MinionModifierData.Hopper hopper = mods.hopper(hopperId);
                        Long cost = prices.acquisitionCost(hopperId);
                        if (hopper == null || cost == null) {
                            continue;
                        }
                        hopperCut = hopper.cut;
                        hopperCost = fromOwned && block != null
                                && hopperId.equals(block.hopperId()) ? 0 : cost;
                    }
                    considered[0]++;
                    MinionMath.Assumptions assumptions = new MinionMath.Assumptions(
                            params.collectionIntervalHours(), params.taxRate(), speed, outputMult,
                            hopperCut, fitted, params.xpMult(), fuel, copies);
                    MinionMath.Projection projection = MinionMath.project(
                            minion, tier, data, mods, prices, assumptions, fuelDrain);
                    double xp = xpForSkill(projection, params.xpSkill(), params.xpMult());
                    // A bundle's figures are for all its copies at once: k times the per-copy
                    // cost and output, at the boost those k copies give each other.
                    long upfront = (baseCost + fuelUpfront + upgradeCost + hopperCost) * copies;
                    group.variants.add(new Variant(minion.type, minion.name, tier.tier, fuelId,
                            List.of(pair), hopperId, fromOwned, fromTier, upfront,
                            projection.netCoinsPerDay() * copies, xp * copies, 0, projection,
                            copies));
                }
            }
        }
    }

    /** XP per day in ONE skill: recomputed from the streams so a cross-skill drop never leaks in. */
    private static double xpForSkill(MinionMath.Projection projection, String skill, double xpMult) {
        double xp = 0;
        for (MinionMath.ValuedStream stream : projection.streams()) {
            if (stream.stream().xpKnown() && stream.stream().xpSkill().equalsIgnoreCase(skill)) {
                xp += stream.stream().itemsPerDay() * projection.utilization()
                        * stream.stream().xpPerItem() * xpMult;
            }
        }
        return xp;
    }

    /** Pareto prune on (upfront, value): keep only variants no cheaper option dominates. */
    private static void prune(Group group) {
        group.variants.sort(Comparator.comparingLong(Variant::upfront));
        List<Variant> kept = new ArrayList<>();
        double bestValue = Double.NEGATIVE_INFINITY;
        for (Variant variant : group.variants) {
            double value = variant.netCoinsPerDay() + variant.xpPerDay() * 1e-9;
            if (value > bestValue + 1e-9) {
                bestValue = value;
                kept.add(variant);
            }
        }
        // Keep the frontier manageable; the DP cost is linear in it.
        while (kept.size() > 6) {
            kept.remove(pickLeastMarginal(kept));
        }
        group.variants = kept;
    }

    private static int pickLeastMarginal(List<Variant> kept) {
        int worst = 1;
        double least = Double.MAX_VALUE;
        for (int i = 1; i < kept.size() - 1; i++) {
            double gain = kept.get(i + 1).netCoinsPerDay() - kept.get(i - 1).netCoinsPerDay();
            if (gain < least) {
                least = gain;
                worst = i;
            }
        }
        return worst;
    }

    /** Re-evaluates the pruned survivors under an island-wide speed boost (the beacon loop). */
    private static List<Group> reevaluate(List<Group> groups, MinionData data,
                                          MinionModifierData mods, MinionMath.Prices prices,
                                          Params params, double extraSpeed, int[] considered) {
        List<Group> out = new ArrayList<>();
        for (Group group : groups) {
            Group boosted = new Group(group.type, group.cap, group.fromOwned, group.fromTier,
                    group.ownedSource);
            MinionData.Minion minion = byType(data, group.type);
            if (minion == null) {
                continue;
            }
            for (Variant variant : group.variants) {
                MinionData.Tier tier = minion.tier(variant.targetTier());
                if (tier == null) {
                    continue;
                }
                addVariantExact(boosted, minion, tier, variant, data, mods, prices, params,
                        extraSpeed, considered);
            }
            prune(boosted);
            if (!boosted.variants.isEmpty()) {
                out.add(boosted);
            }
        }
        return out;
    }

    /** Re-projects one surviving variant with extra island-wide speed, keeping its costs. */
    private static void addVariantExact(Group group, MinionData.Minion minion, MinionData.Tier tier,
                                        Variant variant, MinionData data, MinionModifierData mods,
                                        MinionMath.Prices prices, Params params, double extraSpeed,
                                        int[] considered) {
        MinionModifierData.Fuel fuel = variant.fuelId().isEmpty() ? null : mods.fuel(variant.fuelId());
        double speed = (fuel == null ? 0 : fuel.speedPct) + extraSpeed;
        double outputMult = fuel == null ? 1.0 : fuel.outputMult;
        double fuelDrain = 0;
        if (fuel != null && !fuel.permanent) {
            Long unit = prices.acquisitionCost(fuel.id);
            fuelDrain = MinionMath.fuelCostPerDay(fuel, unit);
        }
        List<MinionModifierData.Upgrade> fitted = new ArrayList<>(variant.upgradeIds().size());
        for (String upgradeId : variant.upgradeIds()) {
            MinionModifierData.Upgrade upgrade = mods.upgrade(upgradeId);
            if (upgrade != null) {
                fitted.add(upgrade);
            }
        }
        Double hopperCut = null;
        if (!variant.hopperId().isEmpty()) {
            MinionModifierData.Hopper hopper = mods.hopper(variant.hopperId());
            hopperCut = hopper == null ? null : hopper.cut;
        }
        considered[0]++;
        // A bundle keeps its copy count through the re-evaluation: its boost, cost and output are
        // all per-bundle, and dropping to 1 here would quietly halve the Inferno plan's value.
        int copies = Math.max(1, variant.slotsEach());
        MinionMath.Assumptions assumptions = new MinionMath.Assumptions(
                params.collectionIntervalHours(), params.taxRate(), speed, outputMult,
                hopperCut, fitted, params.xpMult(), fuel, copies);
        MinionMath.Projection projection = MinionMath.project(minion, tier, data, mods, prices,
                assumptions, fuelDrain);
        group.variants.add(new Variant(variant.type(), variant.name(), variant.targetTier(),
                variant.fuelId(), variant.upgradeIds(), variant.hopperId(), variant.fromOwned(),
                variant.fromTier(), variant.upfront(), projection.netCoinsPerDay() * copies,
                xpForSkill(projection, params.xpSkill(), params.xpMult()) * copies, 0, projection,
                copies));
    }

    // ------------------------------------------------------------------ the DP

    private static final class Best {
        final List<Line> setup;
        final double value;
        int beaconTier;
        long beaconUpfront;
        double beaconDrain;

        Best(List<Line> setup, double value, int beaconTier) {
            this.setup = setup;
            this.value = value;
            this.beaconTier = beaconTier;
        }
    }

    /**
     * Exact bounded knapsack over (slots x bucketed budget): for every group, choose a variant
     * and a copy count. Choices are recorded per layer so the winning setup is reconstructable.
     */
    private static Best runDp(List<Group> groups, long budget, Params params) {
        int slots = Math.max(0, Math.min(64, params.slots()));
        long unit = Math.max(1, budget / BUCKETS);
        int buckets = (int) Math.min(BUCKETS, budget / unit) + 1;

        // Objective scales for BLEND, from the best single-copy values on offer.
        double bestCoins = 1;
        double bestXp = 1;
        for (Group group : groups) {
            for (Variant variant : group.variants) {
                bestCoins = Math.max(bestCoins, variant.netCoinsPerDay());
                bestXp = Math.max(bestXp, variant.xpPerDay());
            }
        }

        double[][] dp = new double[slots + 1][buckets];
        double[][] next = new double[slots + 1][buckets];
        // choice[layer][slot][bucket] = variantIndex * 128 + count, -1 = "take nothing".
        int[][][] choice = new int[groups.size()][slots + 1][buckets];

        for (int layer = 0; layer < groups.size(); layer++) {
            Group group = groups.get(layer);
            for (int s = 0; s <= slots; s++) {
                for (int b = 0; b < buckets; b++) {
                    next[s][b] = dp[s][b];
                    choice[layer][s][b] = -1;
                }
            }
            for (int v = 0; v < group.variants.size(); v++) {
                Variant variant = group.variants.get(v);
                double value = value(variant, params, bestCoins, bestXp);
                if (value <= 0) {
                    continue;
                }
                int costBuckets = (int) Math.min(buckets, ceilDiv(variant.upfront(), unit));
                int slotsEach = Math.max(1, variant.slotsEach());
                for (int count = 1; count <= group.cap && count * slotsEach <= slots; count++) {
                    long totalCost = (long) costBuckets * count;
                    if (totalCost >= buckets) {
                        break;
                    }
                    int slotsUsed = count * slotsEach;
                    for (int s = slots; s >= slotsUsed; s--) {
                        for (int b = buckets - 1; b >= totalCost; b--) {
                            double candidate = dp[s - slotsUsed][b - (int) totalCost] + value * count;
                            if (candidate > next[s][b]) {
                                next[s][b] = candidate;
                                choice[layer][s][b] = v * 128 + count;
                            }
                        }
                    }
                }
            }
            double[][] swap = dp;
            dp = next;
            next = swap;
        }

        // Best cell, then walk the layers back to the lines that produced it.
        int bestS = 0;
        int bestB = 0;
        double bestValue = 0;
        for (int s = 0; s <= slots; s++) {
            for (int b = 0; b < buckets; b++) {
                if (dp[s][b] > bestValue) {
                    bestValue = dp[s][b];
                    bestS = s;
                    bestB = b;
                }
            }
        }
        List<Line> lines = new ArrayList<>();
        int s = bestS;
        int b = bestB;
        for (int layer = groups.size() - 1; layer >= 0; layer--) {
            int encoded = choice[layer][s][b];
            if (encoded < 0) {
                continue;
            }
            Group group = groups.get(layer);
            Variant variant = group.variants.get(encoded / 128);
            int count = encoded % 128;
            lines.add(new Line(variant, count));
            int costBuckets = (int) Math.min(buckets, ceilDiv(variant.upfront(), unit));
            s -= count * Math.max(1, variant.slotsEach());
            b -= costBuckets * count;
        }
        return new Best(lines, bestValue, 0);
    }

    private static double value(Variant variant, Params params, double bestCoins, double bestXp) {
        return switch (params.objective()) {
            case COINS -> variant.netCoinsPerDay();
            case XP -> variant.xpPerDay() + Math.max(0, variant.netCoinsPerDay()) * 1e-9;
            case BLEND -> 0.5 * variant.netCoinsPerDay() / bestCoins
                    + 0.5 * variant.xpPerDay() / bestXp;
        };
    }

    /**
     * The beacon drain in objective units. Only the pure-coins objective can weigh a coin drain
     * directly; XP and BLEND values live on other scales, so there the drain is excluded from the
     * beacon comparison (it still lands in the reported coins/day) - stated in the notes.
     */
    private static double drainValue(double drainPerDay, Params params) {
        return params.objective() == Objective.COINS ? drainPerDay : 0;
    }

    private static long ceilDiv(long a, long b) {
        return (a + b - 1) / b;
    }

    // ------------------------------------------------------------------ advice

    private static String beaconNote(double[] totals, int chosen, int slotsUsed) {
        if (chosen > 0) {
            double gain = totals[chosen] - totals[0];
            return String.format(Locale.ROOT,
                    "Beacon %s worth it at your %d minions (+%.0f/day objective value vs none)",
                    "I II III IV V".split(" ")[chosen - 1], slotsUsed, gain);
        }
        double bestAbove = Double.NEGATIVE_INFINITY;
        for (int tier = 1; tier < totals.length; tier++) {
            bestAbove = Math.max(bestAbove, totals[tier]);
        }
        if (bestAbove == Double.NEGATIVE_INFINITY) {
            return "Beacon tiers unpriceable - not evaluated";
        }
        return String.format(Locale.ROOT,
                "No beacon: best beacon plan trails by %.0f/day at this budget and slot count",
                totals[0] - bestAbove);
    }

    /** Family crystals against the finished setup: worth it when payback beats the horizon. */
    private static List<CrystalAdvice> crystalAdvice(List<Line> setup, MinionData data,
                                                     MinionModifierData mods,
                                                     MinionMath.Prices prices, Params params) {
        List<CrystalAdvice> advice = new ArrayList<>();
        for (MinionModifierData.Crystal crystal : mods.crystals) {
            if (crystal.skill == null) {
                continue; // narrowed crystals (Winter) stay out of the generic advice
            }
            double affected = 0;
            for (Line line : setup) {
                MinionData.Minion minion = byType(data, line.variant().type());
                if (minion != null && crystal.skill.equals(minion.skill)) {
                    // The crystal's added value approximates linearly: +X% speed on an additive
                    // stack of S% scales the uncapped rate by (1+S+X)/(1+S).
                    affected += line.variant().netCoinsPerDay() * line.count()
                            * crystal.speedPct / 100.0;
                }
            }
            if (affected <= 0) {
                continue;
            }
            Long cost = prices.acquisitionCost(crystal.id);
            if (cost == null) {
                continue;
            }
            double payback = cost / affected;
            advice.add(new CrystalAdvice(crystal.id, crystal.name, cost, affected, payback,
                    payback <= params.horizonDays()));
        }
        advice.sort(Comparator.comparingDouble(CrystalAdvice::paybackDays));
        return advice;
    }

    /** How close the runner-up was: the strongest variant left out vs the weakest line chosen. */
    private static String sensitivity(List<Line> setup, List<Group> groups) {
        if (setup.isEmpty()) {
            return "";
        }
        Line weakest = setup.get(0);
        for (Line line : setup) {
            if (line.variant().netCoinsPerDay() < weakest.variant().netCoinsPerDay()) {
                weakest = line;
            }
        }
        Variant bestOut = null;
        for (Group group : groups) {
            for (Variant variant : group.variants) {
                boolean chosen = false;
                for (Line line : setup) {
                    if (line.variant().type().equals(variant.type())
                            && line.variant().targetTier() == variant.targetTier()) {
                        chosen = true;
                        break;
                    }
                }
                if (!chosen && (bestOut == null
                        || variant.netCoinsPerDay() > bestOut.netCoinsPerDay())) {
                    bestOut = variant;
                }
            }
        }
        if (bestOut == null) {
            return "";
        }
        double gap = weakest.variant().netCoinsPerDay() - bestOut.netCoinsPerDay();
        return String.format(Locale.ROOT,
                "Next best left out: %s %s at %.0f/day (%s the weakest chosen line by %.0f/day)",
                bestOut.name(), roman(bestOut.targetTier()), bestOut.netCoinsPerDay(),
                gap >= 0 ? "trails" : "BEATS", Math.abs(gap));
    }

    private static MinionData.Minion byType(MinionData data, String type) {
        for (MinionData.Minion minion : data.minions) {
            if (minion.type.equals(type)) {
                return minion;
            }
        }
        return null;
    }

    private static String roman(int tier) {
        String[] numerals = {"0", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X",
                "XI", "XII"};
        return tier >= 0 && tier < numerals.length ? numerals[tier] : String.valueOf(tier);
    }
}
