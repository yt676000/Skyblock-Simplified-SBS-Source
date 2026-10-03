/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.logic;

import sbs.modid.client.helper.rift.model.Certainty;
import sbs.modid.client.skills.mining.model.HotmData;
import sbs.modid.client.skills.mining.model.HotmStrategyData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Ranks the next Heart of the Mountain levels for one goal profile. Pure: catalogue, tree and
 * powder in, advice out - no game, no clock - so every rule below is unit-tested.
 *
 * <p><b>The score</b> of a step is goal-weighted effect gained divided by the powder it costs, in the
 * perk's own powder. Scores are only compared inside one bucket, in this order:
 * <ol>
 *   <li><b>core</b> perks of the profile before <b>fill</b> perks ("always max core first");</li>
 *   <li>inside a bucket, steps with a known cost by score; then token unlocks by gain; then steps
 *       whose cost is unknown by gain alone, each marked {@link Step#costKnown()} = false.</li>
 * </ol>
 * A step never gets cost 0 to make it rankable: the catalogue javadoc's warning is that a wrong cost
 * inverts the recommendation, and "free" is the most wrong cost there is.
 *
 * <p><b>Unlock order</b> (first rule that applies):
 * <ul>
 *   <li>owned (level &gt; 0) - levelling is always allowed up to the max;</li>
 *   <li>the menu printed {@code Requires X} for it - blocked, with that reason;</li>
 *   <li>its tier is above the highest unlocked tier - blocked;</li>
 *   <li>the menu showed it locked with no {@code Requires} line - reachable, costs a token;</li>
 *   <li>not seen in the menu, position known - reachable when an orthogonal neighbour is owned;</li>
 *   <li>not seen, position unknown (the ESTIMATED tiers) - listed, marked "path unverified".</li>
 * </ul>
 * Display only. Nothing here clicks a perk.
 */
public final class HotmAdvisor {

    private HotmAdvisor() {
    }

    /** What the advisor knows about the player. */
    public record Tree(Map<String, Integer> levels, Map<String, HotmTreeStore.NodeState> nodes,
                       int unlockedTier, int tokens, Map<String, Long> powder) {

        int level(String id) {
            Integer level = levels.get(id);
            return level == null ? 0 : level;
        }

        long powder(String key) {
            Long amount = powder.get(key);
            return amount == null ? -1 : amount;
        }
    }

    /**
     * One recommended move.
     *
     * @param cost      powder for the whole step, or -1 when unknown ({@link #costKnown()} false)
     * @param unlock    the step is a token unlock (level 0 -> 1)
     * @param note      a caveat the UI prints: "path unverified", "no token", "estimated perk"...
     */
    public record Step(HotmData.Perk perk, int from, int to, double gain, long cost, String powder,
                       boolean core, boolean unlock, long have, String note) {

        public boolean costKnown() {
            return cost > 0;
        }

        public double score() {
            return costKnown() ? gain / cost : 0;
        }

        public boolean affordable() {
            return unlock || (costKnown() && have >= 0 && have >= cost);
        }
    }

    /** A perk that cannot be taken yet, and why. */
    public record Blocked(HotmData.Perk perk, String reason) {
    }

    /** Progress towards one perk of the recommended tree. */
    public record Target(HotmData.Perk perk, int level, int target, boolean core, String reason) {
    }

    public record Advice(List<Step> steps, List<Blocked> blocked, List<Target> recommended,
                         List<HotmStrategyData.Pick> skip, double progress) {

        public List<Step> top(int n) {
            return steps.subList(0, Math.min(n, steps.size()));
        }
    }

    public static Advice advise(List<HotmData.Perk> perks, HotmStrategyData.Profile profile, Tree tree) {
        Set<String> core = ids(profile.core);
        Set<String> fill = ids(profile.fill);
        Set<String> skip = ids(profile.skip);

        List<Step> steps = new ArrayList<>();
        List<Blocked> blocked = new ArrayList<>();
        for (HotmData.Perk perk : perks) {
            if (perk.kind == HotmData.Kind.CORE || skip.contains(perk.id)) {
                continue;
            }
            int level = tree.level(perk.id);
            HotmTreeStore.NodeState node = tree.nodes().get(perk.id);
            int max = node != null && node.maxLevel > 0 ? node.maxLevel : perk.maxLevel;
            int cap = Math.min(max, target(profile, perk.id, max));
            if (level >= cap) {
                continue;
            }
            double unitGain = gain(profile, perk, level, level + 1);
            if (unitGain <= 0) {
                continue;   // moves nothing this goal values
            }
            String block = blockReason(perk, level, node, tree, perks);
            if (block != null) {
                blocked.add(new Blocked(perk, block));
                continue;
            }
            // Perks the profile does not name still rank, after fill: a weighted stat is a weighted stat.
            steps.add(step(perk, level, cap, node, tree, profile, core.contains(perk.id)));
        }
        steps.sort(order(core, fill));

        List<Target> recommended = new ArrayList<>();
        long reached = 0;
        long wanted = 0;
        for (List<HotmStrategyData.Pick> group : List.of(profile.core, profile.fill)) {
            for (HotmStrategyData.Pick pick : group) {
                HotmData.Perk perk = find(perks, pick.perk);
                if (perk == null || pick.target <= 0) {
                    continue;
                }
                int level = tree.level(perk.id);
                recommended.add(new Target(perk, level, pick.target, group == profile.core, pick.reason));
                reached += Math.min(level, pick.target);
                wanted += pick.target;
            }
        }
        double progress = wanted == 0 ? 0 : (double) reached / wanted;
        return new Advice(List.copyOf(steps), List.copyOf(blocked), List.copyOf(recommended),
                List.copyOf(profile.skip), progress);
    }

    // ------------------------------------------------------------------ steps

    private static Step step(HotmData.Perk perk, int level, int cap, HotmTreeStore.NodeState node,
                             Tree tree, HotmStrategyData.Profile profile, boolean core) {
        String note = perk.certainty() == Certainty.CONFIRMED ? "" : "estimated perk";
        if (level == 0) {
            if (node == null && perk.column < 0) {
                note = join(note, "path unverified");
            }
            if (tree.tokens() == 0) {
                note = join(note, "no token");
            }
            return new Step(perk, 0, 1, gain(profile, perk, 0, 1), -1, "TOKEN", core, true,
                    tree.tokens(), note);
        }
        String powder = perk.powderKey();
        long have = tree.powder(powder);
        // The first step: the menu's own price beats any formula.
        long first = node != null && node.level == level && node.nextCost > 0 ? node.nextCost
                : perk.stepCost(level);
        if (first <= 0) {
            return new Step(perk, level, level + 1, gain(profile, perk, level, level + 1), -1, powder,
                    core, false, have, join(note, "cost unknown: open HotM"));
        }
        // Extend while affordable and every further step is known: "Gem Lover 12 -> 20".
        long total = first;
        int to = level + 1;
        while (to < cap && have >= 0) {
            long next = perk.stepCost(to);
            if (next <= 0 || total + next > have) {
                break;
            }
            total += next;
            to++;
        }
        return new Step(perk, level, to, gain(profile, perk, level, to), total, powder, core, false,
                have, note);
    }

    /** Goal-weighted value gained going from {@code from} to {@code to}. */
    static double gain(HotmStrategyData.Profile profile, HotmData.Perk perk, int from, int to) {
        double total = 0;
        for (HotmData.Effect effect : perk.effects) {
            total += profile.weight(effect.stat) * (effect.at(to) - effect.at(from));
        }
        return total;
    }

    private static String blockReason(HotmData.Perk perk, int level, HotmTreeStore.NodeState node,
                                      Tree tree, List<HotmData.Perk> perks) {
        if (level > 0) {
            return null;
        }
        if (node != null && node.requires != null && !node.requires.isBlank()) {
            return "requires " + node.requires;
        }
        if (perk.tier > tree.unlockedTier()) {
            return "needs HotM tier " + perk.tier;
        }
        if (node != null || perk.column < 0) {
            return null;   // the menu showed it reachable, or the position is unknown (noted on the step)
        }
        for (HotmData.Perk other : perks) {
            if (other.column < 0 || tree.level(other.id) <= 0) {
                continue;
            }
            boolean sameTier = other.tier == perk.tier && Math.abs(other.column - perk.column) == 1;
            boolean sameColumn = other.column == perk.column && Math.abs(other.tier - perk.tier) == 1;
            if (sameTier || sameColumn) {
                return null;
            }
        }
        return perk.tier == 1 ? null : "no unlocked neighbour";
    }

    private static Comparator<Step> order(Set<String> core, Set<String> fill) {
        return Comparator.<Step>comparingInt(s -> bucket(s, core, fill))
                .thenComparingInt(s -> s.costKnown() ? 0 : s.unlock() ? 1 : 2)
                .thenComparing(Comparator.comparingDouble(
                        (Step s) -> s.costKnown() ? s.score() : s.gain()).reversed())
                .thenComparing(s -> s.perk().name);
    }

    private static int bucket(Step step, Set<String> core, Set<String> fill) {
        return core.contains(step.perk().id) ? 0 : fill.contains(step.perk().id) ? 1 : 2;
    }

    private static int target(HotmStrategyData.Profile profile, String id, int max) {
        for (List<HotmStrategyData.Pick> group : List.of(profile.core, profile.fill)) {
            for (HotmStrategyData.Pick pick : group) {
                if (pick.perk.equals(id) && pick.target > 0) {
                    return pick.target;
                }
            }
        }
        return max;
    }

    private static Set<String> ids(List<HotmStrategyData.Pick> picks) {
        Set<String> out = new HashSet<>();
        for (HotmStrategyData.Pick pick : picks) {
            out.add(pick.perk.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private static HotmData.Perk find(List<HotmData.Perk> perks, String id) {
        for (HotmData.Perk perk : perks) {
            if (perk.id.equals(id)) {
                return perk;
            }
        }
        return null;
    }

    private static String join(String a, String b) {
        return a.isEmpty() ? b : a + ", " + b;
    }
}
