/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.model;

import sbs.modid.client.core.data.VersionedDocument;
import sbs.modid.client.helper.rift.model.Certainty;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Heart of the Mountain perk catalogue ({@code hotm.json}): every perk, where it sits in the
 * tree, what it costs per level and what it does.
 *
 * <p><b>Why a bundled file and not an API call.</b> Hypixel publishes none of this. The profile API
 * carries which perks a player has, but nothing about what a perk <i>costs</i> or <i>gives</i>.
 * Tiers 1-5 were rebuilt from the maintainer's own menu tooltips; tiers 6-10 are wiki leads marked
 * {@link Certainty#ESTIMATED} until a menu shows them.
 *
 * <p><b>The costs are the part that has to be right</b>, because they scale steeply and the whole
 * point of the advisor is benefit per powder. A cost formula is only trusted for ranking when its
 * {@link Perk#costCertainty} is {@link Certainty#CONFIRMED} - that is, it reproduces a cost the menu
 * printed - or when the menu itself stated that step ({@link Perk#levels}). Anything weaker is
 * "cost unknown", never zero: a wrong cost inverts the recommendation.
 *
 * <p><b>Effects are typed by stat</b> ({@link Effect#stat}), so a goal profile weights stats rather
 * than naming perks, and a tree change needs only a data edit.
 */
public final class HotmData implements VersionedDocument {

    public int schemaVersion = 2;
    public int dataVersion;
    public String generatedAt = "";

    /** The highest Heart of the Mountain tier this table describes. */
    public int maxTier;

    public List<Perk> perks = new ArrayList<>();

    /** How a node is bought and levelled. */
    public enum Kind {
        /** One token to unlock, then powder per level. */
        LEVELLED,
        /** One token, a single level, no powder. */
        TOKEN,
        /** A pickaxe ability: one token, levelled by Core of the Mountain rather than powder. */
        ABILITY,
        /** Core of the Mountain: its own upgrade path, never ranked. */
        CORE
    }

    /** One perk and its whole level ladder. */
    public static final class Perk {
        public String id = "";
        public String name = "";
        /** The Heart of the Mountain tier the perk sits on. */
        public int tier;
        /** Menu column (slot % 9), or {@code -1} when the position is not known. */
        public int column = -1;
        public Kind kind = Kind.LEVELLED;
        public int maxLevel;
        /** MITHRIL / GEMSTONE / GLACITE, or empty for a token-only node. */
        public String powder = "";
        /** How sure the identity, tier and effect are. Tier 6+ is ESTIMATED as a block. */
        public String certainty = Certainty.ESTIMATED.name();
        public List<Effect> effects = new ArrayList<>();
        /** The cost formula, or {@code null} when none is known. */
        public CostFormula cost;
        public String costCertainty = Certainty.UNKNOWN.name();

        /**
         * Costs the menu stated, index {@code n} being the step from level {@code n} to {@code n+1}.
         * Filled at runtime by the reader; overrides the formula for that step.
         */
        public List<Level> levels = new ArrayList<>();

        public String powderKey() {
            return powder == null ? "" : powder.trim().toUpperCase(Locale.ROOT);
        }

        public Certainty certainty() {
            return certaintyOf(certainty);
        }

        public Certainty costCertainty() {
            return certaintyOf(costCertainty);
        }

        public boolean levelled() {
            return kind == Kind.LEVELLED && maxLevel > 1;
        }

        /** Cost of the step from {@code level} to {@code level + 1} as the menu stated it, or null. */
        public Level step(int level) {
            if (level < 0 || level >= levels.size()) {
                return null;
            }
            Level step = levels.get(level);
            return step == null || step.cost <= 0 ? null : step;
        }

        /**
         * Powder for the step from {@code level} to {@code level + 1}, or {@code -1} when it is not
         * known well enough to rank on. A menu-stated cost always wins; otherwise the formula, but
         * only a CONFIRMED one.
         */
        public long stepCost(int level) {
            if (level < 0 || level >= maxLevel) {
                return -1;
            }
            Level stated = step(level);
            if (stated != null) {
                return stated.cost;
            }
            if (cost != null && costCertainty() == Certainty.CONFIRMED) {
                return cost.step(level + 1);
            }
            return -1;
        }

        /**
         * Total powder to go from {@code from} to {@code to}, or {@code -1} when any step in between
         * is unknown. {@code -1} rather than a partial sum: a total that silently skipped the levels it
         * did not know would rank the perk as a bargain precisely because the data is thin.
         */
        public long totalCost(int from, int to) {
            long total = 0;
            for (int level = from; level < to; level++) {
                long step = stepCost(level);
                if (step <= 0) {
                    return -1;
                }
                total += step;
            }
            return total;
        }

        /**
         * Records a price the menu stated for the step from {@code level} to {@code level + 1}. Levels
         * below it stay unknown: the menu only ever quotes the next step.
         */
        public void learn(int level, long stepCost) {
            if (level < 0 || stepCost <= 0) {
                return;
            }
            while (levels.size() <= level) {
                levels.add(new Level());
            }
            Level step = levels.get(level);
            step.cost = stepCost;
            step.certainty = Certainty.CONFIRMED.name();
        }

        /** Value of {@code stat} at {@code level}; zero at level 0 or for a stat the perk does not move. */
        public double value(String stat, int level) {
            double total = 0;
            for (Effect effect : effects) {
                if (effect.stat.equals(stat)) {
                    total += effect.at(level);
                }
            }
            return total;
        }
    }

    /** One stat a perk moves: {@code first} at level 1, plus {@code perLevel} per level after. */
    public static final class Effect {
        public String stat = "";
        public double first;
        public double perLevel;

        public double at(int level) {
            return level <= 0 ? 0 : first + perLevel * (level - 1);
        }
    }

    /** {@code floor((level + offset) ^ exponent)} for the step that reaches {@code level}. */
    public static final class CostFormula {
        public double exponent;
        public int offset = 1;

        public long step(int reachedLevel) {
            return (long) Math.floor(Math.pow(reachedLevel + offset, exponent));
        }
    }

    /** One level's price as the menu stated it. */
    public static final class Level {
        public long cost;
        public String certainty = Certainty.CONFIRMED.name();

        public Certainty certainty() {
            return certaintyOf(certainty);
        }
    }

    /**
     * A certainty tag as the file spelled it. An unrecognised tag is the weakest thing it could be,
     * never the strongest: a typo must not promote a guess to a confirmed number.
     */
    static Certainty certaintyOf(String raw) {
        try {
            return Certainty.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            return Certainty.ESTIMATED;
        }
    }

    @Override
    public int schemaVersion() {
        return schemaVersion;
    }

    @Override
    public int dataVersion() {
        return dataVersion;
    }

    @Override
    public boolean valid() {
        return perks != null && !perks.isEmpty();
    }
}
