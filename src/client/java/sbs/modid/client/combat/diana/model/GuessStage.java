/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.diana.model;

/**
 * How far a guess got before it gave up.
 *
 * <h2>Why a feature needs this at all</h2>
 *
 * <p>Both guess systems are pipelines of half a dozen stages, and every stage can decline. Declining
 * is usually the right answer - a wrong guess sends the player walking, which is worse than no guess
 * - but a pipeline that declines silently is indistinguishable from one that is switched off, from
 * one whose particles never arrived, and from one that is simply broken. "It does nothing" was the
 * first thing reported about this feature, and nothing in it could say which of those it was.
 *
 * <p>None of the constants these pipelines run on has been verified against this client, so the
 * question "which stage stopped" is not a debugging nicety here - it is the difference between a
 * correction that takes one line of JSON and a session of guessing. The stage a guess reached is
 * therefore part of what {@code /sbs diana} reports, not something only a log carries.
 *
 * <p>One enum for both pipelines, because the question is the same one and the answer belongs in the
 * same readout. {@link #appliesToArrow} and {@link #appliesToSpade} say which stages a given
 * pipeline can actually reach, so neither reports a stage it has no way to be in.
 */
public enum GuessStage {

    /** Nothing has been collected. For the spade this means the ability has not been seen at all. */
    IDLE("nothing collected yet", true, true),

    /** Particles are arriving but there are not yet enough of them to attempt anything. */
    COLLECTING("collecting particles", true, true),

    /**
     * Enough arrow particles arrived, but no straight run long enough to be a shaft was found among
     * them.
     *
     * <p>The interesting failure. It means the particles are arriving and being recognised, and the
     * geometry - the run length, the step tolerance, the collinearity epsilon - is what did not fit.
     */
    NO_SHAFT("particles arrived but no straight run was found in them", true, false),

    /**
     * A shaft was found, and the density test at its two ends refused to say which one is the head.
     *
     * <p>Rejecting here is deliberate: an arrow fitted backwards produces a guess pointing exactly
     * 180 degrees wrong, delivered with the same confidence as a correct one. But it is also the
     * stage most likely to be tripped by a wrong constant, because it demands exact neighbour counts.
     */
    AMBIGUOUS_ENDS("a shaft was found but neither end looked like the head", true, false),

    /** The two ends of the shaft coincide, so there is no direction to cast. */
    DEGENERATE("the fitted shaft has no direction", true, false),

    /** The Echo trail has no direction yet - its points sit on top of each other - or no rule is loaded. */
    FIT_FAILED("the trail has no direction yet", false, true),

    /**
     * The Echo trail arrived but its harp notes did not rise, so there is no distance to read.
     *
     * <p>Either the notes are not reaching the toolkit (the sound hook) or another sound was taken
     * for them; the trail's direction alone is not a guess, because it says nothing about how far.
     */
    NO_PITCH("the trail arrived but its notes did not rise - no distance", false, true),

    /** A landing point was computed and it fell outside the Hub box the document carries. */
    OUTSIDE_HUB("the answer landed outside the Hub box in the data file", true, true),

    /**
     * The arrow cloud filled up in the seconds after a dig without any fit succeeding, and was
     * dropped. Nothing more is collected until the next dig.
     *
     * <p>Usually the earlier stage it kept stopping at is the real answer - the give-up line in the
     * log names it. On its own this means a busy Hub, or an arrow shape the constants do not match.
     */
    GAVE_UP("the particles near the dig never formed an arrow - waiting for the next dig", true, false),

    /** The ray was cast and no block along it looked like a burrow. */
    NO_LANDING("the direction was found but no block along it looked like a burrow", true, false),

    /** A guess was produced. */
    READY("a guess is up", true, true);

    private final String explanation;
    private final boolean arrow;
    private final boolean spade;

    GuessStage(String explanation, boolean arrow, boolean spade) {
        this.explanation = explanation;
        this.arrow = arrow;
        this.spade = spade;
    }

    /** What this stage means, in the words the player's own readout uses. */
    public String explanation() {
        return explanation;
    }

    public boolean appliesToArrow() {
        return arrow;
    }

    public boolean appliesToSpade() {
        return spade;
    }

    /** Whether this stage is one where something is still expected to happen. */
    public boolean working() {
        return this == IDLE || this == COLLECTING || this == READY;
    }
}
