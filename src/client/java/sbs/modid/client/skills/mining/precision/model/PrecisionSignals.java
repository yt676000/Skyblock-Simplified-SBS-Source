/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.precision.model;

import sbs.modid.client.helper.rift.model.Certainty;

/**
 * Everything the Precision Mining target assumes about the game, in one place, each with how sure
 * we are of it.
 *
 * <p><b>What is confirmed.</b> The perk itself: the Heart of the Mountain menu (capture
 * 2026-09-28, slot 30) describes it as "a particle target appears on the block that increases your
 * Mining Speed by 30% when aiming at it", and {@code hotm.json} carries it as
 * {@code precision_mining}. That is all.
 *
 * <p><b>What is not.</b> The particle - its type, its count, where on the block it sits, how often
 * it is resent - and what "aiming at it" means to the server. Every value below the perk id was
 * written 2026-10-04 from the task brief, before any capture, and is {@link Certainty#ESTIMATED}.
 * The {@code [SBS][Precision]} log lines are the probe: one mining session prints the real packet.
 * Promote one value at a time: replace it, change its tag, and add the date it was seen.
 */
public final class PrecisionSignals {

    private PrecisionSignals() {
    }

    // ------------------------------------------------------------------ the perk

    /** The perk's id in {@code hotm.json} and the HotM tree cache. */
    public static final String PERK_ID = "precision_mining";
    public static final Certainty PERK_CERTAINTY = Certainty.CONFIRMED;

    // ------------------------------------------------------------------ the particle

    /**
     * The particle type id, or empty for "any type". Empty until a probe names it: matching on a
     * guessed id would leave the marker silently dark, while "any single particle on the face"
     * at worst marks something that is not the target - visibly, and the probe log says what it was.
     */
    public static final String PARTICLE_TYPE = "";
    /**
     * A packet carrying more than this many particles is a burst, not the target. The target is
     * expected as a single particle ({@code count} 0 or 1).
     */
    public static final int MAX_COUNT = 1;
    /** How far from a face of the mined block the particle may sit and still be on that face. */
    public static final double FACE_TOLERANCE = 0.05;
    /**
     * Sightings of one type on one block before it counts as the target. The target is expected to
     * be resent while the block is mined; a single stray particle is not.
     */
    public static final int REQUIRED_SIGHTINGS = 2;
    /** The target is dropped this long after the last matching particle. */
    public static final long TARGET_STALE_MS = 1_000L;
    public static final Certainty PARTICLE_CERTAINTY = Certainty.ESTIMATED;

    // ------------------------------------------------------------------ on target

    /**
     * The default "on target" radius in blocks, between where the crosshair meets the block and the
     * particle. The player can change it; the server's own tolerance is not known.
     */
    public static final double DEFAULT_ON_TARGET_RADIUS = 0.15;
    public static final Certainty ON_TARGET_CERTAINTY = Certainty.ESTIMATED;

    // ------------------------------------------------------------------ the probe

    /** Particles within this distance of the mined block's centre are logged by the probe. */
    public static final double PROBE_RADIUS = 1.5;
}
