/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.diana.logic;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * How many burrow chains are running, which is a surprisingly small piece of state for something
 * that sounds complicated.
 *
 * <h2>A chain is an expiry, and nothing else</h2>
 *
 * <p>The client is never told how many chains it has. What it can see is when one starts, when one
 * is advanced and when one ends - so a chain is stored as the moment it will have gone stale, and
 * the count is how many of those are still in the future. Starting pushes an expiry; advancing moves
 * the oldest one forward; ending or dying pops one.
 *
 * <p>Moving the <b>oldest</b> expiry on an advance rather than a matching one is deliberate: there
 * is nothing in a progress line that says which chain it belongs to, and the oldest is the one
 * closest to being dropped. Guessing in that direction keeps a live chain alive; guessing the other
 * way would quietly drop one that is still running.
 *
 * <h2>This is a display, and it is allowed to be wrong</h2>
 *
 * <p>The chains are the server's. Ours is an inference from chat lines, so a client that joined
 * mid-event, missed a message or reconnected has a count that is too low. That is why it is shown
 * as a count and never used as a gate: nothing is refused because this says zero.
 */
public final class ChainTracker {

    private static final ChainTracker INSTANCE = new ChainTracker();

    /**
     * How long a chain survives without being advanced.
     *
     * <p>Thirty minutes is what the behaviour is described with, and it is a figure nobody has
     * watched expire - so the worst case is a card that says three when it should say two, which is
     * a cosmetic error in a readout the player can see the age of.
     */
    private static final long CHAIN_TTL_MS = 30 * 60 * 1000L;

    /** Most chains that can run at once. Over this the count is capped rather than the queue grown. */
    private static final int MAX_CHAINS = 7;

    /** Expiry timestamps, oldest first. */
    private final Deque<Long> expiries = new ArrayDeque<>();

    private ChainTracker() {
    }

    public static ChainTracker getInstance() {
        return INSTANCE;
    }

    /** How many chains are running. Prunes what has expired on the way past. */
    public int count() {
        prune();
        return expiries.size();
    }

    /** The first burrow of a chain was dug. */
    public void started() {
        prune();
        if (expiries.size() >= MAX_CHAINS) {
            // At the cap the count is already saying "as many as can run"; adding another would make
            // the readout say something the game cannot do.
            return;
        }
        expiries.addLast(System.currentTimeMillis() + CHAIN_TTL_MS);
        DianaDebug.getInstance().note("chain started, " + expiries.size() + " running");
    }

    /** A middle burrow of some chain was dug: whichever chain it was, it is not stale. */
    public void advanced() {
        prune();
        if (expiries.isEmpty()) {
            // A progress line for a chain we never saw start - joined mid-event, or a message was
            // missed. Adopting it is better than ignoring it: the chain demonstrably exists.
            expiries.addLast(System.currentTimeMillis() + CHAIN_TTL_MS);
            return;
        }
        expiries.removeFirst();
        expiries.addLast(System.currentTimeMillis() + CHAIN_TTL_MS);
    }

    /** A chain ended - finished, or failed by dying. */
    public void ended() {
        prune();
        if (!expiries.isEmpty()) {
            expiries.removeFirst();
        }
        DianaDebug.getInstance().note("chain ended, " + expiries.size() + " running");
    }

    /** World change, server hop, island change, or the player asking. */
    public void reset() {
        expiries.clear();
    }

    private void prune() {
        long now = System.currentTimeMillis();
        while (!expiries.isEmpty() && expiries.peekFirst() <= now) {
            expiries.removeFirst();
        }
    }

    /**
     * The raw queue, for the guard's error report: each chain's milliseconds to expiry, expired ones
     * included. Deliberately not pruned - a snapshot must not change what it reports.
     */
    public JsonObject snapshot() {
        long now = System.currentTimeMillis();
        JsonArray remaining = new JsonArray();
        for (Long expiry : expiries) {
            remaining.add(expiry - now);
        }
        JsonObject out = new JsonObject();
        out.addProperty("queued", expiries.size());
        out.add("msToExpiry", remaining);
        return out;
    }

    /** How long until the oldest chain goes stale, in milliseconds, or {@code 0} when none is running. */
    public long oldestRemainingMs() {
        prune();
        Long first = expiries.peekFirst();
        return first == null ? 0L : Math.max(0L, first - System.currentTimeMillis());
    }
}
