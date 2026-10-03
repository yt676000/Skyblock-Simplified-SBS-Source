/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.foraging.model;

/**
 * One chop, as Hypixel reported it in the per-chop foraging detail message.
 *
 * <p><b>Absent is not zero.</b> Every numeric field carries {@link #ABSENT} when the message did
 * not contain it, and a card never renders an absent field - it omits the row. The convention is
 * the one {@code FarmingFortuneDisplay.ToolFortune} already uses for "the lore did not say", and it
 * exists for the same reason: a confident {@code 0} where the game said nothing is the one output
 * worse than no output.
 *
 * <p>{@link Delivery#UNKNOWN} is a first-class answer rather than a parse failure. Whether the
 * message distinguishes a thrown axe from a melee swing at all is an open question; if it turns out
 * not to, {@code UNKNOWN} is permanently correct and the card simply never shows that row.
 *
 * @param effectiveSweep the sweep that actually applied to this chop, conditional bonuses included
 * @param toughness      the tree's toughness, when the message carried it
 * @param blocksBroken   how many blocks this chop took, when the message carried it
 * @param delivery       melee, thrown, or not stated
 * @param at             when this client saw the message
 */
public record SweepChop(double effectiveSweep, double toughness, int blocksBroken,
                        Delivery delivery, long at) {

    /** The "the message did not say" sentinel. Never rendered. */
    public static final double ABSENT = -1;

    /** How the axe reached the tree. */
    public enum Delivery {
        MELEE, THROWN, UNKNOWN
    }

    /** Whether this chop carried an effective sweep at all - the one field the card needs. */
    public boolean hasSweep() {
        return effectiveSweep >= 0;
    }

    public boolean hasToughness() {
        return toughness >= 0;
    }

    public boolean hasBlocks() {
        return blocksBroken >= 0;
    }
}
