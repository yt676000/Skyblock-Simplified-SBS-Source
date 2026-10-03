/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.nucleus.logic;

import sbs.modid.client.skills.mining.nucleus.model.NucleusCostRules;
import sbs.modid.client.skills.mining.nucleus.model.NucleusSignals;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pairs a cost line with the inventory decrease that shows what was used. Pure - lines, decreases
 * and a clock in, bookings out.
 *
 * <p>Which arrives first is not known, so a decrease is remembered for
 * {@link NucleusSignals#COST_WINDOW_MS} and a line waits that long for one. A rule with a candidate set
 * collects every matching decrease in the window (three Goblin Eggs can leave as three stacks) and is
 * settled when the window closes; a wildcard rule (empty set) takes the first decrease only. Each
 * decrease is used once. Decreases are only fed while no container screen is open - in a menu they
 * are sales, trades and storage moves.
 */
public final class CostWindow {

    /**
     * One settled cost line.
     *
     * @param itemId     the item's id, or {@code null} when only {@code itemName} is known
     * @param itemName   the name the line gave, or {@code null}
     * @param qty        how many; {@code 0} when {@code unresolved}
     * @param estimated  booked from the rule's fallback, not from an observed decrease
     * @param unresolved nothing was seen and the rule has no fallback - nothing is booked
     */
    public record Outcome(NucleusCostRules.Rule rule, String itemId, String itemName, long qty,
                          boolean estimated, boolean unresolved) {
    }

    private record Decrease(String id, long qty, long at) {
    }

    private static final class Pending {
        final NucleusCostRules.Rule rule;
        final long at;
        final Map<String, Long> taken = new LinkedHashMap<>();

        Pending(NucleusCostRules.Rule rule, long at) {
            this.rule = rule;
            this.at = at;
        }
    }

    private final Deque<Decrease> recent = new ArrayDeque<>();
    private final List<Pending> pending = new ArrayList<>();

    /** A cost line arrived. Lines that prove their item are settled at once. */
    public List<Outcome> onLine(NucleusCostRules.Rule rule, String itemName, long now) {
        List<Outcome> out = new ArrayList<>();
        poll(now, out);
        if (rule.fixedItemId() != null) {
            out.add(new Outcome(rule, rule.fixedItemId(), null, 1L, false, false));
            return out;
        }
        if (rule.nameFromLine()) {
            out.add(new Outcome(rule, null, itemName, 1L, false, false));
            return out;
        }
        Pending waiting = new Pending(rule, now);
        Iterator<Decrease> it = recent.iterator();
        while (it.hasNext()) {
            Decrease decrease = it.next();
            if (take(waiting, decrease)) {
                it.remove();
                if (waiting.rule.candidates().isEmpty()) {
                    break;
                }
            }
        }
        if (waiting.rule.candidates().isEmpty() && !waiting.taken.isEmpty()) {
            settle(waiting, out);
        } else {
            pending.add(waiting);
        }
        return out;
    }

    /** An item left the inventory with no container open. */
    public List<Outcome> onDecrease(String id, long qty, long now) {
        List<Outcome> out = new ArrayList<>();
        poll(now, out);
        Decrease decrease = new Decrease(id, qty, now);
        for (Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
            Pending waiting = it.next();
            if (take(waiting, decrease)) {
                if (waiting.rule.candidates().isEmpty()) {
                    it.remove();
                    settle(waiting, out);
                }
                return out;
            }
        }
        recent.addLast(decrease);
        return out;
    }

    /** Settles lines whose window has closed. Called every tick. */
    public List<Outcome> poll(long now) {
        List<Outcome> out = new ArrayList<>();
        poll(now, out);
        return out;
    }

    /** Whether any line is still waiting - the capture log reports decreases while one is. */
    public boolean waiting() {
        return !pending.isEmpty();
    }

    /** World change: nothing pairs across it. */
    public void clear() {
        recent.clear();
        pending.clear();
    }

    private void poll(long now, List<Outcome> out) {
        while (!recent.isEmpty() && now - recent.peekFirst().at > NucleusSignals.COST_WINDOW_MS) {
            recent.removeFirst();
        }
        for (Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
            Pending waiting = it.next();
            if (now - waiting.at > NucleusSignals.COST_WINDOW_MS) {
                it.remove();
                settle(waiting, out);
            }
        }
    }

    private static boolean take(Pending waiting, Decrease decrease) {
        if (Math.abs(decrease.at - waiting.at) > NucleusSignals.COST_WINDOW_MS) {
            return false;
        }
        boolean any = waiting.rule.candidates().isEmpty();
        if (any ? !waiting.taken.isEmpty() : !waiting.rule.candidates().contains(decrease.id)) {
            return false;
        }
        waiting.taken.merge(decrease.id, decrease.qty, Long::sum);
        return true;
    }

    private static void settle(Pending waiting, List<Outcome> out) {
        NucleusCostRules.Rule rule = waiting.rule;
        if (!waiting.taken.isEmpty()) {
            waiting.taken.forEach((id, qty) -> out.add(new Outcome(rule, id, null, qty, false, false)));
        } else if (rule.fallbackItemId() != null) {
            out.add(new Outcome(rule, rule.fallbackItemId(), null, 1L, true, false));
        } else {
            out.add(new Outcome(rule, null, null, 0L, false, true));
        }
    }
}
