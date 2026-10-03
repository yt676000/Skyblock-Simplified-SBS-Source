/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.logic;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.util.NumberDisplay;
import sbs.modid.client.skills.mining.model.HotmStrategyData;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The live inputs for {@link HotmAdvisor} and the wording both the screen and the menu panel print,
 * so the two can never phrase the same step differently.
 *
 * <p><b>Powder source.</b> The tab widget's {@code Powders:} section ({@link MiningTracker}) when it
 * has served a value, because it is live; otherwise the HotM menu header captured at the last
 * reading. The tab parse was rewritten on 2026-08-08 and no mining session has run in the logs since,
 * so the header - whose shape is confirmed - is what the advisor falls back on.
 */
public final class HotmAdvice {

    private HotmAdvice() {
    }

    public static int goalIndex() {
        return Math.max(0, ConfigManager.getInstance().get().miningHelpers.hotmAdvisorGoal);
    }

    public static HotmStrategyData.Profile goal() {
        return HotmStrategies.at(goalIndex());
    }

    /** Advice for the selected goal, or {@code null} when a data file failed to load. */
    public static HotmAdvisor.Advice current() {
        HotmStrategyData.Profile profile = goal();
        if (profile == null || HotmCatalog.perks().isEmpty()) {
            return null;
        }
        return HotmAdvisor.advise(HotmCatalog.perks(), profile, tree());
    }

    public static HotmAdvisor.Tree tree() {
        HotmTreeStore store = HotmTreeStore.getInstance();
        Map<String, Long> powder = new LinkedHashMap<>(store.menuPowder());
        for (Map.Entry<String, Long> tab : MiningTracker.getInstance().powder().entrySet()) {
            powder.put(tab.getKey().toUpperCase(Locale.ROOT), tab.getValue());
        }
        return new HotmAdvisor.Tree(store.levels(), store.nodes(), store.unlockedTier(), store.tokens(),
                powder);
    }

    /** "Gem Lover 12 → 20" / "Sky Mall: unlock". */
    public static String title(HotmAdvisor.Step step) {
        return step.unlock() ? step.perk().name + ": unlock"
                : step.perk().name + " " + step.from() + " → " + step.to();
    }

    /** "costs 13.3k Gemstone Powder, you have 7k" / "1 token, you have 0" / "cost unknown: open HotM". */
    public static String cost(HotmAdvisor.Step step) {
        if (step.unlock()) {
            return "1 Token of the Mountain" + (step.have() >= 0 ? ", you have " + step.have() : "");
        }
        if (!step.costKnown()) {
            return "cost unknown: open HotM";
        }
        String kind = step.powder().isEmpty() ? "" : step.powder().charAt(0)
                + step.powder().substring(1).toLowerCase(Locale.ROOT) + " Powder";
        return "costs " + NumberDisplay.format(step.cost()) + " " + kind
                + (step.have() >= 0 ? ", you have " + NumberDisplay.format(step.have()) : "");
    }

    /** "+40 mining fortune" for every stat the step moves, in the tooltip's own units. */
    public static String effect(HotmAdvisor.Step step) {
        StringBuilder out = new StringBuilder();
        for (var effect : step.perk().effects) {
            double delta = effect.at(step.to()) - effect.at(step.from());
            if (delta == 0) {
                continue;
            }
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append('+').append(trim(delta)).append(' ').append(effect.stat.replace('_', ' '));
        }
        return out.toString();
    }

    private static String trim(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value)
                : String.format(Locale.ROOT, "%.2f", value).replaceAll("0+$", "");
    }
}
