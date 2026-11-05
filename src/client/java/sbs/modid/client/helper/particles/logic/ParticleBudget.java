/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.particles.logic;

import java.util.Arrays;

/**
 * Per-slot token buckets for "Limit particle spam": each slot (a particle type's registry id, plus
 * any extra slots the caller reserves) holds at most {@code cap} tokens and is topped back up to its
 * cap once per client tick. A spawn takes one token; a spawn with none left is dropped unless the
 * caller's {@link Exemption} says the particle is one a feature depends on.
 *
 * <p>Fixed arrays sized once, no Minecraft types - so the rules are unit-testable and the spawn path
 * allocates nothing. Single-threaded: every caller is on the client thread.
 */
public final class ParticleBudget {

    /** Cap value for a slot that is never limited. */
    public static final int UNLIMITED = -1;

    /** Lazily asked whether the particle being admitted must pass regardless of the bucket. */
    @FunctionalInterface
    public interface Exemption {
        boolean exempt();
    }

    private final int[] caps;
    private final int[] tokens;
    private final int[] dropped;
    private final int[] droppedLastSecond;

    public ParticleBudget(int slots) {
        caps = new int[slots];
        tokens = new int[slots];
        dropped = new int[slots];
        droppedLastSecond = new int[slots];
        Arrays.fill(caps, UNLIMITED);
    }

    public int size() {
        return caps.length;
    }

    /** Sets every slot back to {@link #UNLIMITED}; the caller then applies its caps. */
    public void clearCaps() {
        Arrays.fill(caps, UNLIMITED);
    }

    public void setCap(int slot, int cap) {
        if (slot >= 0 && slot < caps.length) {
            caps[slot] = cap < 0 ? UNLIMITED : cap;
            tokens[slot] = caps[slot];
        }
    }

    public int cap(int slot) {
        return slot >= 0 && slot < caps.length ? caps[slot] : UNLIMITED;
    }

    /**
     * Whether one particle in {@code slot} may spawn. Unlimited and out-of-range slots always pass;
     * otherwise a token is taken, and only an empty bucket asks {@code exemption} - so the signature
     * check costs nothing until a type is actually being capped. Exempt particles take no token.
     */
    public boolean admit(int slot, Exemption exemption) {
        if (slot < 0 || slot >= caps.length || caps[slot] < 0) {
            return true;
        }
        if (tokens[slot] > 0) {
            tokens[slot]--;
            return true;
        }
        if (exemption != null && exemption.exempt()) {
            return true;
        }
        dropped[slot]++;
        return false;
    }

    /** Once per client tick: every bucket back to its cap. */
    public void refill() {
        System.arraycopy(caps, 0, tokens, 0, caps.length);
    }

    /** Once a second: publishes the drops counted since the last call and restarts the count. */
    public void rollSecond() {
        System.arraycopy(dropped, 0, droppedLastSecond, 0, dropped.length);
        Arrays.fill(dropped, 0);
    }

    /** Particles dropped in {@code slot} during the last full second. */
    public int droppedPerSecond(int slot) {
        return slot >= 0 && slot < droppedLastSecond.length ? droppedLastSecond[slot] : 0;
    }
}
