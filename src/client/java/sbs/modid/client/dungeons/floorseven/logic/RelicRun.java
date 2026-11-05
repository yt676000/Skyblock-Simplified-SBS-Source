/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.floorseven.logic;

import sbs.modid.client.dungeons.floorseven.model.RelicColour;

import java.util.EnumMap;
import java.util.Map;

/**
 * The relics of one M7 dragon phase as a state machine, free of the game so it can be tested:
 * each colour goes {@link State#SPAWNED} → {@link State#PICKED} → {@link State#PLACED}, never back.
 *
 * <ul>
 *   <li>A pickup by anyone moves its colour to {@code PICKED} - its spawn marker is no use to anyone
 *       once the relic is gone from it.</li>
 *   <li>A pickup by the local player also makes it {@link #carrying()}: that is the one relic whose
 *       target is shown.</li>
 *   <li>{@link #placed} ends the carry. Only the local player's placement is observable; a teammate's
 *       relic stays {@code PICKED}, which draws nothing either way.</li>
 * </ul>
 *
 * <p>Every transition reports whether it changed anything, and {@link #version()} counts changes, so
 * the caller republishes markers on a change and never per tick.
 */
public final class RelicRun {

    public enum State { SPAWNED, PICKED, PLACED }

    private final Map<RelicColour, State> states = new EnumMap<>(RelicColour.class);
    private RelicColour carrying;
    private int version;

    public RelicRun() {
        reset();
    }

    /** A new run: every relic back at its spawn, nothing carried. */
    public void reset() {
        for (RelicColour colour : RelicColour.values()) {
            states.put(colour, State.SPAWNED);
        }
        carrying = null;
        version++;
    }

    public State state(RelicColour colour) {
        return states.get(colour);
    }

    /** The relic the local player picked and has not placed, or {@code null}. */
    public RelicColour carrying() {
        return carrying;
    }

    /** Whether any relic has been picked this run - proof the relic part of the phase has begun. */
    public boolean anyPicked() {
        for (State state : states.values()) {
            if (state != State.SPAWNED) {
                return true;
            }
        }
        return false;
    }

    public int version() {
        return version;
    }

    /** Applies a pickup line. Returns whether anything changed. */
    public boolean picked(RelicPickup pickup) {
        if (pickup == null) {
            return false;
        }
        boolean changed = false;
        if (states.get(pickup.colour()) == State.SPAWNED) {
            states.put(pickup.colour(), State.PICKED);
            changed = true;
        }
        if (pickup.self() && states.get(pickup.colour()) == State.PICKED && carrying != pickup.colour()) {
            carrying = pickup.colour();
            changed = true;
        }
        if (changed) {
            version++;
        }
        return changed;
    }

    /** The local player placed {@code colour}. Returns whether anything changed. */
    public boolean placed(RelicColour colour) {
        if (colour == null || states.get(colour) != State.PICKED) {
            return false;
        }
        states.put(colour, State.PLACED);
        if (carrying == colour) {
            carrying = null;
        }
        version++;
        return true;
    }

    /**
     * Whether {@code colour}'s spawn marker is shown under {@code filter}: the relic is still at its
     * spawn, and the filter is {@code ALL} (or unreadable) or names that colour.
     */
    public boolean showSpawn(RelicColour colour, String filter) {
        return states.get(colour) == State.SPAWNED && passes(colour, filter);
    }

    /** {@code ALL}, empty, null or an unknown word pass everything; a colour id passes only itself. */
    public static boolean passes(RelicColour colour, String filter) {
        if (filter == null) {
            return true;
        }
        for (RelicColour candidate : RelicColour.values()) {
            if (candidate.name().equalsIgnoreCase(filter.trim())) {
                return candidate == colour;
            }
        }
        return true;
    }
}
