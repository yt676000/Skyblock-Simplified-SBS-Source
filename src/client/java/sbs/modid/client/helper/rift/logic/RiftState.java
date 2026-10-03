/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.rift.logic;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.location.SkyBlockLocation;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The one place a Rift feature asks "am I in the Rift, and did I just arrive or just leave".
 *
 * <p><b>Why this is not just a location check.</b> Every Rift feature accumulates state that is only
 * meaningful for one visit - the time you entered with, the motes you have earned this trip, which
 * effigies you saw standing. {@link SkyBlockLocation#island()} can answer "am I there" on demand, but
 * nothing derived from it tells a feature <i>when to throw its state away</i>, and each feature
 * inventing its own edge detector is how one of them ends up carrying a stale session across a warp.
 *
 * <p><b>Three things reset a visit</b>, and they are deliberately not the same event:
 * <ul>
 *   <li><b>Leaving</b> the Rift - the island stops being "The Rift". This is the ordinary case, and
 *       it is what {@link Listener#onRiftExit()} is for.</li>
 *   <li><b>Entering</b> - the island becomes "The Rift" again. A feature that only listens for the
 *       exit would still be holding the previous visit's numbers on the frame the new one starts,
 *       so both edges are published.</li>
 *   <li><b>A server hop</b> - the world is swapped without the island ever changing. Warping from one
 *       Rift instance to another looks identical to standing still from the island's point of view,
 *       and it is exactly the case where the timer restarts. {@link #onWorldChange()} is called from
 *       the client's world-change path and forces an exit/enter pair.</li>
 * </ul>
 *
 * <p>Polled from the client tick rather than pushed: the location itself is a polled reading (the
 * scoreboard and tab list are just text that changes), so an "event" here is always an edge this
 * class noticed, never one Hypixel announced.
 */
public final class RiftState {

    /** How {@link SkyBlockLocation#island()} spells the Rift. */
    public static final String ISLAND = "The Rift";

    private static final RiftState INSTANCE = new RiftState();

    /** What a feature implements to be told about the edges. */
    public interface Listener {

        /** The player has just arrived in the Rift - start a fresh visit. */
        default void onRiftEnter() {
        }

        /** The player has just left it - drop whatever the visit accumulated. */
        default void onRiftExit() {
        }
    }

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private volatile boolean inRift;

    /** When the current visit started, epoch millis; {@code 0} while not in the Rift. */
    private volatile long enteredAt;

    /** How many visits this session - only for the status line, so a hop is visibly a new visit. */
    private volatile int visits;

    private RiftState() {
    }

    public static RiftState getInstance() {
        return INSTANCE;
    }

    /**
     * Registers a listener for the lifetime of the process.
     *
     * <p>No unregister on purpose: every caller is a singleton feature service that registers once at
     * construction, and an unregister that is never called is a method that only ever gets used wrong.
     */
    public void register(Listener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    // ------------------------------------------------------------------ queries

    /** Whether the player is in the Rift right now. Every Rift feature gates on this. */
    public boolean inRift() {
        return inRift;
    }

    /** How long the current visit has lasted, in milliseconds; {@code 0} while outside. */
    public long visitMillis() {
        long start = enteredAt;
        return start == 0L ? 0L : System.currentTimeMillis() - start;
    }

    /** Visits seen this session, counting server hops as separate visits. */
    public int visits() {
        return visits;
    }

    // ------------------------------------------------------------------ driving

    /**
     * Re-reads the location and fires the edges. Called every client tick; cheap, because
     * {@link SkyBlockLocation} answers off its own 250 ms cache.
     */
    public void tick() {
        boolean now = SkyBlockLocation.onIsland(ISLAND);
        if (now == inRift) {
            return;
        }
        if (now) {
            enter();
        } else {
            exit();
        }
    }

    /**
     * A world swap. Forces a fresh visit when the player is (still) in the Rift, because a hop
     * between two Rift instances never changes the island and would otherwise go unnoticed - and a
     * hop is precisely when the time you have left is reset.
     */
    public void onWorldChange() {
        if (inRift) {
            exit();
        }
        // The new world's scoreboard is not populated yet, so entering is left to the next tick()
        // rather than guessed here. Anything that was holding visit state has already been told to
        // drop it, which is the half that has to be immediate.
    }

    private void enter() {
        inRift = true;
        enteredAt = System.currentTimeMillis();
        visits++;
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Rift] entered (visit {} this session)", visits);
        for (Listener listener : listeners) {
            try {
                listener.onRiftEnter();
            } catch (RuntimeException e) {
                SkyblockSimplifiedSBS.LOGGER.debug("[SBS][Rift] enter listener failed: {}", e.toString());
            }
        }
    }

    private void exit() {
        inRift = false;
        enteredAt = 0L;
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Rift] left");
        for (Listener listener : listeners) {
            try {
                listener.onRiftExit();
            } catch (RuntimeException e) {
                SkyblockSimplifiedSBS.LOGGER.debug("[SBS][Rift] exit listener failed: {}", e.toString());
            }
        }
    }
}
