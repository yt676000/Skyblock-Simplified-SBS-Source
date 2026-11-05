/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.puzzle.model;

/**
 * How far one puzzle's solver has got, which decides its settings row: a finished solver is on by
 * default, a probe-only one is off by default, and a puzzle with no solver gets a greyed-out row.
 *
 * <p>Kept next to {@link PuzzleType} rather than inferred from which strategies the coordinator
 * registers, because "registered" and "finished" are different things - a probe-only solver is
 * registered and highlights nothing.
 */
public enum SolverStage {

    /** Solves and highlights. Default on. */
    FINISHED,
    /** Runs, but only records what it sees for the solver to be written from; draws nothing. Default off. */
    PROBE_ONLY,
    /** No solver exists. The row is shown disabled so the list says which puzzles are covered. */
    NONE;

    /** Whether the per-puzzle toggle starts on. */
    public boolean defaultOn() {
        return this == FINISHED;
    }

    /** Whether the toggle can be used at all. */
    public boolean available() {
        return this != NONE;
    }
}
