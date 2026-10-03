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
import java.util.List;

/**
 * The minion production / profit / XP arithmetic, as pure functions over explicit inputs - no
 * Minecraft, no live caches, so every rule is unit-testable and every projection is reproducible
 * from its stated {@link Assumptions}.
 *
 * <p>The rules that make or break the numbers, all verified against the wiki 2026-08-07:
 * <ul>
 *   <li><b>Every other action produces.</b> A harvest takes {@code actionsPerHarvest} actions
 *       (2 for all but Fishing/Melon/Pumpkin), so the lore's action time understates the drop
 *       period by that factor.</li>
 *   <li><b>Speed boosts are additive</b> and shorten time as {@code base / (1 + total/100)} -
 *       never as a multiplicative time reduction.</li>
 *   <li><b>Multiplier fuels multiply drops</b>, not speed (Tasty Cheese, Catalysts, Derpy).</li>
 *   <li><b>A full minion stops.</b> The capped rate scales by {@code min(1, timeToFull/interval)};
 *       a hopper removes the cap but pays the NPC price times its cut, and hopper-sold items grant
 *       no collection XP.</li>
 *   <li><b>XP lands on collection</b>, per collected item - post-compactor, since compacting
 *       changes what is collected.</li>
 * </ul>
 *
 * <p>Deliberate v1 simplifications, stated rather than hidden: storage is compared in items
 * against the lore's item capacity (slot fragmentation across drop types is ignored); hopper mode
 * sells everything (no partial manual collection on top); the Super Compactor is modeled at its
 * final product (the catalog's sc3000 steps), reached whenever storage allows.
 */
public final class MinionMath {

    /** Where a unit value came from - every shown figure carries its label. */
    public enum PriceSource { BAZAAR, NPC, AH, UNKNOWN }

    /**
     * Live lookups the engine needs, kept behind an interface so tests can fix prices.
     * {@code null} means unknown - the engine never substitutes a guess.
     */
    public interface Prices {
        /** Bazaar instant-sell (what selling gets you), pre-tax. */
        Long bazaarSell(String itemId);

        /** NPC merchant price per unit, or {@code null} when the item has none. */
        Double npcSell(String itemId);

        /** Lowest BIN by display name (the AH cache is name-keyed), or {@code null}. */
        Long lbin(String itemName);

        /** Cheapest acquisition per unit (buy vs recursive craft), for costs. */
        Long acquisitionCost(String lookupId);

        /** Units actually sold per day (moving-week / 7), or {@code 0} when unknown. */
        long dailySellVolume(String itemId);
    }

    /**
     * The player-stated context of a projection. {@code speedPct} and {@code outputMult} arrive
     * pre-aggregated (additive percents summed, multipliers multiplied) - what to include is the
     * caller's UI decision; the engine only insists on the arithmetic.
     *
     * @param collectionIntervalHours how often the player empties minions
     * @param taxRate                 bazaar sell tax as a fraction (from the Bazaar Flipper level)
     * @param speedPct                total additive speed percentage
     * @param outputMult              total drop multiplier (fuel x mayor)
     * @param hopperCut               NPC-price fraction a hopper pays, or {@code null} = no hopper
     * @param upgrades                what sits in the minion's TWO upgrade slots; every kind is
     *                                applied here (speed, output multiplier, smelting, item
     *                                replacement, added drops, compaction, spreading, cooldown
     *                                streams) and an upgrade the minion does not accept is ignored
     * @param xpMult                  skill-XP multiplier (Wisdom, events), 1 = none
     */
    public record Assumptions(double collectionIntervalHours, double taxRate, double speedPct,
                              double outputMult, Double hopperCut,
                              List<MinionModifierData.Upgrade> upgrades, double xpMult,
                              MinionModifierData.Fuel fuel, int sameTypePlaced) {

        public Assumptions {
            upgrades = upgrades == null ? List.of() : List.copyOf(upgrades);
            sameTypePlaced = Math.max(1, sameTypePlaced);
        }

        /** Without the fuel object: speed/output already aggregated, no Inferno mechanics. */
        public Assumptions(double collectionIntervalHours, double taxRate, double speedPct,
                           double outputMult, Double hopperCut,
                           List<MinionModifierData.Upgrade> upgrades, double xpMult) {
            this(collectionIntervalHours, taxRate, speedPct, outputMult, hopperCut, upgrades,
                    xpMult, null, 1);
        }

        /** The bare case: no upgrade slots filled. */
        public Assumptions(double collectionIntervalHours, double taxRate, double speedPct,
                           double outputMult, Double hopperCut, double xpMult) {
            this(collectionIntervalHours, taxRate, speedPct, outputMult, hopperCut, List.of(), xpMult);
        }

        /** The fuel, but only when this minion actually accepts it. */
        public MinionModifierData.Fuel usableFuel(MinionData.Minion minion) {
            return fuel != null && fuel.appliesTo(minion) ? fuel : null;
        }

        /** The upgrades that actually do something on {@code minion}. */
        public List<MinionModifierData.Upgrade> applicable(MinionData.Minion minion) {
            List<MinionModifierData.Upgrade> out = new ArrayList<>(upgrades.size());
            for (MinionModifierData.Upgrade upgrade : upgrades) {
                if (upgrade != null && upgrade.appliesTo(minion)) {
                    out.add(upgrade);
                }
            }
            return out;
        }

        /**
         * Total ADDITIVE speed: the stated percentage, every applicable upgrade's own, and the
         * minion's self-boost for how many of its type are placed (Rising Celsius).
         */
        public double speedPctWith(MinionData.Minion minion) {
            double total = speedPct;
            for (MinionModifierData.Upgrade upgrade : applicable(minion)) {
                total += upgrade.speedPct;
            }
            return total + minion.selfBoostPct(sameTypePlaced);
        }

        /**
         * The Inferno fuels' multiplicative factor, as the game applies it:
         * {@code time = base / ((1 + additive) * (1 + speedMult))}. The extra 1 is the game's own
         * (documented) quirk, which is why a "20x" fuel really runs at 21x.
         */
        public double speedMultWith(MinionData.Minion minion) {
            MinionModifierData.Fuel usable = usableFuel(minion);
            return usable == null || usable.speedMult <= 0 ? 1.0 : 1.0 + usable.speedMult;
        }

        /** Total drop multiplier: the stated one times every applicable upgrade's (engines halve). */
        public double outputMultWith(MinionData.Minion minion) {
            double total = outputMult;
            for (MinionModifierData.Upgrade upgrade : applicable(minion)) {
                total *= upgrade.outputMult <= 0 ? 1.0 : upgrade.outputMult;
            }
            return total;
        }
    }

    /** One output stream after all transforms: what actually lands in storage. */
    public record Stream(String itemId, String name, double itemsPerDay, double xpPerItem,
                        String xpSkill, boolean xpKnown) {
    }

    /** One valued stream: {@link Stream} plus what a day of it is worth and where that came from. */
    public record ValuedStream(Stream stream, PriceSource source, double unitValue,
                              double coinsPerDay, double marketShare) {
    }

    /**
     * A full projection for one minion tier under one set of assumptions. Uncapped figures ignore
     * storage; effective figures apply the collection interval (or the hopper).
     */
    public record Projection(double harvestsPerDay, double hoursToFull, double utilization,
                            List<ValuedStream> streams, double coinsPerDayUncapped,
                            double coinsPerDay, double fuelCostPerDay, double netCoinsPerDay,
                            double xpPerDay, boolean xpIncomplete) {
    }

    private MinionMath() {
    }

    // ------------------------------------------------------------------
    // Production
    // ------------------------------------------------------------------

    /** Additive boosts, divisive time: {@code base / (1 + pct/100)}. */
    public static double effectiveActionSeconds(double baseSeconds, double speedPct) {
        return baseSeconds / (1.0 + speedPct / 100.0);
    }

    /** Harvests per day at an effective action time - the every-other-action rule lives here. */
    public static double harvestsPerDay(double actionSeconds, int actionsPerHarvest) {
        if (actionSeconds <= 0) {
            return 0;
        }
        return 86400.0 / (actionSeconds * Math.max(1, actionsPerHarvest));
    }

    /**
     * The output streams of one minion under the assumptions: raw drops scaled by the output
     * multiplier, then folded through the Super Compactor's per-minion product steps when enabled.
     * XP facts ride along per stream ({@code xpKnown} false = no fact, contribution unknown).
     */
    public static List<Stream> streams(MinionData.Minion minion, MinionData data,
                                       MinionModifierData mods, double harvestsPerDay,
                                       Assumptions assumptions) {
        List<MinionModifierData.Upgrade> upgrades = assumptions.applicable(minion);
        double outputMult = assumptions.outputMultWith(minion);

        // 0. An Inferno fuel replaces the normal drop on most harvests with its distillate's
        //    specialty item, so the base drops only happen on the remaining share.
        MinionModifierData.Fuel fuel = assumptions.usableFuel(minion);
        boolean specialty = fuel != null && fuel.specialtyItem != null
                && fuel.specialtyChance > 0 && fuel.specialtyAmount > 0;
        double baseShare = specialty ? Math.max(0, 1.0 - fuel.specialtyChance) : 1.0;

        // 1. Base drops, plus whatever the upgrades ADD per harvest (Corrupt Soil's Sulphur and
        //    Corrupted Fragment, the Enchanted Egg's egg, the Shears' wool).
        //
        //    A replacement fuel swaps the drop list out wholesale first: a Flower Minion running
        //    Thorny Vines makes Wild Roses and nothing else, which is the only reason to run one.
        List<Stream> raw = new ArrayList<>();
        if (fuel != null && fuel.replaceItem != null && fuel.replaceAmount > 0) {
            raw.add(new Stream(fuel.replaceItem, fuel.replaceItem,
                    fuel.replaceAmount * harvestsPerDay * outputMult * baseShare, 0, "", false));
        } else {
            for (MinionData.Output output : minion.outputs) {
                raw.add(new Stream(output.item, output.name,
                        output.amount * harvestsPerDay * outputMult * baseShare, 0, "", false));
            }
        }
        if (specialty) {
            raw.add(new Stream(fuel.specialtyItem, fuel.specialtyItem,
                    fuel.specialtyAmount * harvestsPerDay * fuel.specialtyChance * outputMult,
                    0, "", false));
        }
        for (MinionModifierData.Upgrade upgrade : upgrades) {
            if (upgrade.adds == null) {
                continue;
            }
            for (MinionModifierData.Add add : upgrade.adds) {
                if (add != null && add.item != null && !add.item.isBlank() && add.amount > 0) {
                    raw.add(new Stream(add.item, add.item, add.amount * harvestsPerDay * outputMult,
                            0, "", false));
                }
            }
        }

        // 2. Replacements, before any compaction: a smelter changes what there is to compact.
        boolean smelts = false;
        for (MinionModifierData.Upgrade upgrade : upgrades) {
            smelts |= upgrade.smelts || "compact_smelt".equals(upgrade.kind);
        }
        List<Stream> replaced = new ArrayList<>(raw.size());
        for (Stream stream : raw) {
            String itemId = stream.itemId();
            for (MinionModifierData.Upgrade upgrade : upgrades) {
                if (upgrade.from != null && upgrade.to != null
                        && upgrade.from.equalsIgnoreCase(itemId)) {
                    itemId = upgrade.to;
                }
            }
            if (smelts) {
                String smelted = mods.smeltMap.get(itemId);
                if (smelted != null) {
                    itemId = smelted;
                }
            }
            replaced.add(new Stream(itemId, itemId.equals(stream.itemId()) ? stream.name() : itemId,
                    stream.itemsPerDay(), 0, "", false));
        }

        // 3. Compaction. Block forms and enchanted forms are different products with different
        //    prices, so which upgrade is fitted decides what actually lands in storage.
        boolean toEnchanted = false;
        boolean toBlocks = false;
        for (MinionModifierData.Upgrade upgrade : upgrades) {
            toEnchanted |= "compact_ench".equals(upgrade.kind) || "compact_smelt".equals(upgrade.kind);
            toBlocks |= "compact_block".equals(upgrade.kind);
        }
        double harvestedItemsPerDay = 0;
        for (Stream stream : replaced) {
            harvestedItemsPerDay += stream.itemsPerDay();
        }
        List<Stream> out = new ArrayList<>(replaced.size() + 2);
        for (Stream stream : replaced) {
            out.add(finish(compact(minion, data, mods, stream, toEnchanted, toBlocks), data));
        }

        // 4. A spreading upgrade rolls per harvested item and lands its own item in storage.
        for (MinionModifierData.Upgrade upgrade : upgrades) {
            if (!"spread".equals(upgrade.kind) || upgrade.chance <= 0 || upgrade.item == null) {
                continue;
            }
            Stream spread = new Stream(upgrade.item, upgrade.item,
                    harvestedItemsPerDay * upgrade.chance, 0, "", false);
            out.add(finish(compact(minion, data, mods, spread, toEnchanted, toBlocks), data));
        }

        // 5. A cooldown upgrade produces on a timer of its own, not per harvest - and its item is
        //    never compacted (Raw Soulflow and Lush Berberis have no compact form).
        for (MinionModifierData.Upgrade upgrade : upgrades) {
            if (!"cooldown".equals(upgrade.kind) || upgrade.cooldownSeconds == null
                    || upgrade.cooldownSeconds <= 0 || upgrade.item == null) {
                continue;
            }
            out.add(finish(new Stream(upgrade.item, upgrade.item,
                    86400.0 / upgrade.cooldownSeconds, 0, "", false), data));
        }

        // 6. The Legendary Inferno fuel rolls each of its rare drops once per harvest. These are
        //    lottery tickets - an Inferno Apex is one in 1.3 million - so they are carried as
        //    their expected value rather than pretended to be steady income.
        if (fuel != null && fuel.rareDrops != null) {
            for (MinionModifierData.RareDrop drop : fuel.rareDrops) {
                if (drop != null && drop.item != null && drop.chance > 0) {
                    out.add(finish(new Stream(drop.item, drop.item,
                            harvestsPerDay * drop.chance, 0, "", false), data));
                }
            }
        }
        return out;
    }

    /** Runs one stream through the fitted compactor, following the chain as far as it goes. */
    private static Stream compact(MinionData.Minion minion, MinionData data, MinionModifierData mods,
                                  Stream stream, boolean toEnchanted, boolean toBlocks) {
        String itemId = stream.itemId();
        String name = stream.name();
        double itemsPerDay = stream.itemsPerDay();
        if (toEnchanted) {
            // The minion's own product list first (it knows the real end product), then the
            // generic chain for anything it does not mention - a spread Diamond, for instance.
            for (int step = 0; step < 4; step++) {
                MinionData.Sc3000 sc = sc3000Step(minion, itemId);
                if (sc != null) {
                    itemsPerDay /= Math.max(1, sc.ratio);
                    itemId = sc.item;
                    name = sc.item;
                    continue;
                }
                MinionData.Chain chain = data.compactChains.get(itemId);
                if (chain == null || chain.to == null || chain.ratio <= 0) {
                    break;
                }
                itemsPerDay /= chain.ratio;
                itemId = chain.to;
                name = chain.to;
            }
        } else if (toBlocks) {
            MinionModifierData.Compact block = mods.blockMap.get(itemId);
            if (block != null && block.to != null && block.ratio > 0) {
                itemsPerDay /= block.ratio;
                itemId = block.to;
                name = block.to;
            }
        }
        return new Stream(itemId, name, itemsPerDay, 0, "", false);
    }

    /** Attaches the XP fact for whatever the stream finally consists of. */
    private static Stream finish(Stream stream, MinionData data) {
        MinionData.XpRow xp = xpRow(data, stream.itemId());
        return new Stream(stream.itemId(), stream.name(), stream.itemsPerDay(),
                xp == null ? 0 : xp.xp, xp == null ? "" : xp.skill, xp != null);
    }

    private static MinionData.Sc3000 sc3000Step(MinionData.Minion minion, String itemId) {
        if (minion.sc3000 == null) {
            return null;
        }
        for (MinionData.Sc3000 step : minion.sc3000) {
            if (step.from.equals(itemId)) {
                return step;
            }
        }
        return null;
    }

    private static MinionData.XpRow xpRow(MinionData data, String itemId) {
        for (MinionData.XpRow row : data.xp) {
            if (row.item.equals(itemId)) {
                return row;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // The projection
    // ------------------------------------------------------------------

    /**
     * The full projection for one tier. This is THE entry point: production, storage cap,
     * valuation, fuel drain and XP in one pass, so no caller can mix an uncapped rate with a
     * capped value by accident.
     *
     * @param fuelCostPerDay what the chosen fuel burns per day (0 for none/permanent) - computed
     *                       by the caller from its acquisition cost, passed in so the engine stays
     *                       price-source-agnostic
     */
    public static Projection project(MinionData.Minion minion, MinionData.Tier tier,
                                     MinionData data, MinionModifierData mods, Prices prices,
                                     Assumptions assumptions, double fuelCostPerDay) {
        // The additive stack first, then the Inferno fuel's separate multiplicative factor.
        double actionSeconds = effectiveActionSeconds(tier.actionSeconds,
                assumptions.speedPctWith(minion)) / assumptions.speedMultWith(minion);
        double harvests = harvestsPerDay(actionSeconds, minion.actionsPerHarvest);
        List<Stream> streams = streams(minion, data, mods, harvests, assumptions);

        double itemsPerDay = 0;
        for (Stream stream : streams) {
            itemsPerDay += stream.itemsPerDay();
        }
        double hoursToFull = itemsPerDay <= 0 ? Double.POSITIVE_INFINITY
                : tier.storage / itemsPerDay * 24.0;

        boolean hopper = assumptions.hopperCut() != null;
        // A hopper empties the minion; manual collection loses everything past time-to-full.
        double utilization = hopper ? 1.0
                : Math.min(1.0, hoursToFull / Math.max(0.01, assumptions.collectionIntervalHours()));

        List<ValuedStream> valued = new ArrayList<>();
        double coinsUncapped = 0;
        double xpPerDay = 0;
        boolean xpIncomplete = false;
        for (Stream stream : streams) {
            ValuedStream value = value(stream, prices, assumptions);
            valued.add(value);
            coinsUncapped += value.coinsPerDay();
            if (hopper) {
                // Hopper-sold items are never collected, so they grant no skill XP.
            } else if (stream.xpKnown()) {
                xpPerDay += stream.itemsPerDay() * utilization * stream.xpPerItem()
                        * assumptions.xpMult();
            } else if (stream.itemsPerDay() > 0) {
                xpIncomplete = true;
            }
        }
        double coinsPerDay = coinsUncapped * utilization;
        return new Projection(harvests, hoursToFull, utilization, valued, coinsUncapped,
                coinsPerDay, fuelCostPerDay, coinsPerDay - fuelCostPerDay, xpPerDay, xpIncomplete);
    }

    /**
     * One stream's daily value: the better of the taxed Bazaar instant-sell and the NPC price.
     *
     * <p><b>The auction house is deliberately not a candidate.</b> Lowest BIN is what ONE unit
     * costs a buyer, not what a thousand a day are worth to a seller: minion output arrives in
     * bulk, and there is no auction demand for 1.1k Slime Blocks a day. Pricing bulk output at
     * lowest BIN produced exactly that fantasy, so the only sinks that can absorb a minion's
     * output - the Bazaar's order book and the NPC merchant - are the only ones offered. (AH
     * prices are still used for BUYING, where one unit at a time is the real transaction.)
     */
    private static ValuedStream value(Stream stream, Prices prices, Assumptions assumptions) {
        double unit;
        PriceSource source;
        if (assumptions.hopperCut() != null) {
            Double npc = prices.npcSell(stream.itemId());
            unit = npc == null ? 0 : npc * assumptions.hopperCut();
            source = npc == null ? PriceSource.UNKNOWN : PriceSource.NPC;
        } else {
            Long bazaar = prices.bazaarSell(stream.itemId());
            Double npc = prices.npcSell(stream.itemId());
            double bazaarNet = bazaar == null ? -1 : bazaar * (1.0 - assumptions.taxRate());
            double npcValue = npc == null ? -1 : npc;
            if (bazaarNet >= npcValue && bazaarNet >= 0) {
                unit = bazaarNet;
                source = PriceSource.BAZAAR;
            } else if (npcValue >= 0) {
                unit = npcValue;
                source = PriceSource.NPC;
            } else {
                unit = 0;
                source = PriceSource.UNKNOWN;
            }
        }
        long dailyVolume = prices.dailySellVolume(stream.itemId());
        double share = dailyVolume <= 0 ? 0 : stream.itemsPerDay() / dailyVolume;
        return new ValuedStream(stream, source, unit, stream.itemsPerDay() * unit, share);
    }

    // ------------------------------------------------------------------
    // Costs and payback
    // ------------------------------------------------------------------

    /**
     * The marginal cost of reaching {@code tier} from the one below (materials only, previous
     * tier implied), or {@code null} when any line is unpriceable or the tier has no cost data.
     */
    public static Long marginalCost(MinionData.Tier tier, Prices prices) {
        if (tier.cost == null || tier.cost.isEmpty()) {
            return null;
        }
        long total = 0;
        for (MinionData.CostRef line : tier.cost) {
            // Written as an if, NOT as `"COINS".equals(id) ? 1L : prices.acquisitionCost(id)`:
            // mixing a primitive long with a Long makes the conditional unbox BOTH branches, so an
            // unpriceable ingredient throws instead of returning "unknown".
            Long unit;
            if ("COINS".equals(line.id)) {
                unit = 1L;
            } else {
                unit = prices.acquisitionCost(line.id);
            }
            if (unit == null) {
                return null;
            }
            total += Math.round(unit * line.count);
        }
        return total;
    }

    /**
     * The from-scratch cost of owning {@code upToTier} (sum of marginal costs from tier I), or
     * {@code null} when any tier on the way is drop-only (Snow I) or unpriceable - the minion
     * then cannot be bought into existence with coins and the UI must say so, not show a number.
     */
    public static Long fromScratchCost(MinionData.Minion minion, int upToTier, Prices prices) {
        long total = 0;
        for (MinionData.Tier tier : minion.tiers) {
            if (tier.tier > upToTier) {
                break;
            }
            if ("drop".equals(tier.source)) {
                return null;
            }
            Long marginal = marginalCost(tier, prices);
            if (marginal == null) {
                return null;
            }
            total += marginal;
        }
        return total;
    }

    /** Days for {@code upfrontCost} to return at {@code netCoinsPerDay}; infinite when nothing nets. */
    public static double paybackDays(long upfrontCost, double netCoinsPerDay) {
        if (netCoinsPerDay <= 0) {
            return Double.POSITIVE_INFINITY;
        }
        return upfrontCost / netCoinsPerDay;
    }

    /** The tier-upgrade decision: what going up one tier costs against what it adds per day. */
    public record UpgradeDelta(int fromTier, int toTier, long cost, double addedCoinsPerDay,
                              double addedXpPerDay, double paybackDays) {
    }

    /**
     * Evaluates upgrading {@code minion} from {@code fromTier} to the next tier under identical
     * assumptions, or {@code null} when either tier or the cost is unavailable.
     */
    public static UpgradeDelta upgradeDelta(MinionData.Minion minion, int fromTier,
                                            MinionData data, MinionModifierData mods, Prices prices,
                                            Assumptions assumptions, double fuelCostPerDay) {
        MinionData.Tier current = minion.tier(fromTier);
        MinionData.Tier next = minion.tier(fromTier + 1);
        if (current == null || next == null) {
            return null;
        }
        Long cost = marginalCost(next, prices);
        if (cost == null) {
            return null;
        }
        Projection before = project(minion, current, data, mods, prices, assumptions, fuelCostPerDay);
        Projection after = project(minion, next, data, mods, prices, assumptions, fuelCostPerDay);
        double addedCoins = after.netCoinsPerDay() - before.netCoinsPerDay();
        double addedXp = after.xpPerDay() - before.xpPerDay();
        return new UpgradeDelta(fromTier, fromTier + 1, cost, addedCoins, addedXp,
                paybackDays(cost, addedCoins));
    }

    // ------------------------------------------------------------------
    // Fuel
    // ------------------------------------------------------------------

    /**
     * What a consumable fuel burns per day: unit cost over duration. Permanent fuels return 0 -
     * their price is capital (part of the setup cost), not a drain.
     */
    public static double fuelCostPerDay(MinionModifierData.Fuel fuel, Long unitCost) {
        if (fuel == null || fuel.permanent || fuel.durationHours <= 0 || unitCost == null) {
            return 0;
        }
        return unitCost * 24.0 / fuel.durationHours;
    }
}
