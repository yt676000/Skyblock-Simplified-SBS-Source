/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.diana.model;

import net.minecraft.core.BlockPos;

/**
 * One burrow the client knows about: where it is, what it turned out to be, and how far through
 * being dug it is.
 *
 * <h2>Existence and kind are two different facts</h2>
 *
 * <p>Two of the particle roles say a burrow is <i>there</i> without saying what it <i>is</i>. So a
 * record can legitimately exist with a null {@link #kind}, and that state is not a half-built
 * object - it is the honest answer for a burrow whose kind particle has not arrived yet. Nothing
 * draws a kind it does not have; the marker says "Burrow" until the game says otherwise.
 *
 * <h2>Mutable on purpose</h2>
 *
 * <p>A burrow is discovered in pieces, over several packets and several chat lines, and it keeps its
 * identity throughout - the same block, the same marker, the same dig count. A record rather than an
 * immutable value because replacing the object on every new fact is what loses the dig count, and
 * losing the dig count is what makes a marker vanish while the player is still digging it.
 */
public final class BurrowRecord {

    /** The block the burrow sits in. The identity: two records at one block is a bug. */
    public final BlockPos pos;

    /** What it holds, or {@code null} while only its existence is known. */
    public BurrowKind kind;

    /** Whether the "a burrow is here" swirl has been seen at this block. */
    public boolean seenMarker;

    /** Whether a footstep of the trail leading here has been seen. */
    public boolean seenFootstep;

    /**
     * How many times the player has dug this burrow.
     *
     * <p>Carried across every promotion - a guess proven right by particles, a guess dug before the
     * particles arrived - because the count is a fact about the player's actions and not about which
     * of our own data structures currently owns the marker. Reconciled against chat, which wins.
     */
    public int timesDug;

    /** When this record was created, for the debug readout and for ordering. */
    public final long discoveredAt = System.currentTimeMillis();

    /** When anything about it last changed, so a stale record can be told from a live one. */
    public long updatedAt = System.currentTimeMillis();

    public BurrowRecord(BlockPos pos) {
        this.pos = pos;
    }

    /** Whether the kind is known - i.e. whether this is more than "something is here". */
    public boolean classified() {
        return kind != null;
    }

    /**
     * How many digs this burrow takes before its marker has done its job.
     *
     * <p>A start burrow opens a chain and is finished in one; a mob or treasure burrow takes two.
     * <b>Unverified</b> - see {@code SPEC_DIANA.md} §5.1 and the feature's {@code Not done yet}. It
     * is the most visible number in the whole toolkit, because getting it wrong either leaves a
     * marker standing on a hole that is gone or removes one the player is still working on. An
     * unclassified burrow answers two, which is the conservative direction: a marker that outstays
     * its welcome is a nuisance, one that leaves early is a burrow the player never finds.
     */
    public int digsNeeded() {
        return kind == BurrowKind.START ? 1 : 2;
    }

    /** Whether the marker should now go. */
    public boolean finished() {
        return timesDug >= digsNeeded();
    }

    /** The marker's label. Falls back to a kindless name rather than inventing a kind. */
    public String label() {
        return kind == null ? "Burrow" : kind.displayName();
    }

    /** Records a change, so {@link #updatedAt} means what it says without every caller remembering. */
    public void touch() {
        updatedAt = System.currentTimeMillis();
    }

    @Override
    public String toString() {
        return "[" + pos.getX() + " " + pos.getY() + " " + pos.getZ() + "] "
                + label() + " dug=" + timesDug + "/" + digsNeeded()
                + (seenMarker ? " marker" : "") + (seenFootstep ? " footstep" : "");
    }
}
