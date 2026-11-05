/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.floorseven.logic;

import sbs.modid.client.dungeons.floorseven.model.LightsCell;
import sbs.modid.client.dungeons.floorseven.model.LightsLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * What the Lights device needs right now: a pure function of layout, lit lamps and wiring.
 *
 * <p>Recomputed whenever any of the three changes - a teammate's flip changes the lit lamps exactly
 * like ours does, so the levers still to flip are always counted from the board as it is, never from
 * a plan made earlier.
 *
 * @param kind      the outcome
 * @param toFlip    absolute lever positions to flip (any order), empty unless SOLVED or PARTIAL
 * @param known     levers whose wiring is known
 * @param levers    levers in the layout
 * @param minimal   whether {@code toFlip} is a fewest-flips answer over the known levers
 */
public record LightsDeviceState(Kind kind, List<LightsCell> toFlip, int known, int levers, boolean minimal,
                                LightsWiring wiring) {

    public enum Kind {
        /** No device in view. */
        NONE,
        /** Every lamp lit. */
        DONE,
        /** Full wiring known; {@code toFlip} is the answer. */
        SOLVED,
        /** Some levers unknown, but the known ones already solve the board. */
        PARTIAL,
        /** Not enough wiring seen to solve: keep flipping and watching. */
        RECORDING,
        /** Full wiring known and still no answer: the wiring as recorded is wrong. */
        INCONSISTENT
    }

    public static final LightsDeviceState NONE = new LightsDeviceState(Kind.NONE, List.of(), 0, 0, false, null);

    /** Evaluates the board. {@code layout} {@code null} means no device. */
    public static LightsDeviceState evaluate(LightsLayout layout, Set<LightsCell> lit, LightsWiring wiring) {
        if (layout == null || !layout.supported()) {
            return NONE;
        }
        int levers = layout.levers().size();
        int lamps = layout.lamps().size();
        long litMask = layout.lampMask(lit);
        long all = lamps >= 64 ? -1L : (1L << lamps) - 1;
        int known = wiring == null ? 0 : wiring.knownCount();
        if ((litMask & all) == all) {
            return new LightsDeviceState(Kind.DONE, List.of(), known, levers, true, wiring);
        }
        if (wiring == null || known == 0) {
            return new LightsDeviceState(Kind.RECORDING, List.of(), 0, levers, false, wiring);
        }
        boolean complete = wiring.complete(levers);
        LightsSolver.Solution solution = LightsSolver.solve(wiring.columns(), wiring.knownMask(), lamps, litMask);
        if (!solution.solvable()) {
            return new LightsDeviceState(complete ? Kind.INCONSISTENT : Kind.RECORDING,
                    List.of(), known, levers, false, wiring);
        }
        List<LightsCell> toFlip = new ArrayList<>(solution.flips());
        for (int i = 0; i < levers; i++) {
            if ((solution.levers() & (1L << i)) != 0) {
                toFlip.add(layout.levers().get(i));
            }
        }
        return new LightsDeviceState(complete ? Kind.SOLVED : Kind.PARTIAL, List.copyOf(toFlip), known, levers,
                complete && solution.minimal(), wiring);
    }
}
