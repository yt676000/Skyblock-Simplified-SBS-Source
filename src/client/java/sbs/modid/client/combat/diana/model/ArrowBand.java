/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.diana.model;

import java.util.List;

/**
 * One arrow colour and the distance band it stands for.
 *
 * <h2>The colour is in the offsets, and that is not a mistake</h2>
 *
 * <p>The arrow's particles arrive in the directional form - count zero - where vanilla reads the
 * three offset floats as a velocity. Hypixel instead puts a colour triple there. So the fields this
 * class matches are the same three fields {@link BurrowSignature} usually treats as a spread box,
 * read as a message rather than as an address, which is why an arrow signature leaves them unmatched
 * and this table picks them up afterwards.
 *
 * <h2>A range, never a distance</h2>
 *
 * <p>The colour narrows how far away the next burrow is; it does not state it. The bands also
 * <b>overlap at their seams</b> in every source that describes them, deliberately - a burrow sitting
 * exactly on a boundary must not fall through the gap between two ranges. Anything treating a band
 * as a point, or trimming the overlap to make the ranges tidy, reintroduces exactly the hole the
 * overlap exists to close.
 *
 * <h2>Why the shipped table is empty</h2>
 *
 * <p>Three sources describe these colours and no two agree - the current wiki, older community
 * writing, and a reference implementation whose own comments disagree with the triples it matches.
 * A distance band is the worst kind of value to guess at, because a wrong one sends the player
 * walking. With no bands recorded the arrow guess simply does not filter its candidates by range:
 * more candidates to walk, none of them confidently wrong. That is the degradation we want, and it
 * is why {@code DianaParticleData} ships this section empty until a capture settles it.
 */
public final class ArrowBand {

    /** The three offset values this band is carried on. Must be length 3 to be usable. */
    public List<Double> offsets;

    /** Nearest distance in blocks a burrow on this colour may be. */
    public int min;

    /** Furthest distance in blocks a burrow on this colour may be. */
    public int max;

    /**
     * A human name for the colour, for the debug readout only.
     *
     * <p>Never matched on, and deliberately so: the names in circulation contradict the triples in
     * circulation, so the triple is the fact and the name is folklore.
     */
    public String label = "";

    /** Whether this entry can be matched at all. */
    public boolean usable() {
        return offsets != null && offsets.size() == 3 && max > min;
    }

    /** Whether a packet's offsets carry this band, within {@code tolerance} on each channel. */
    public boolean matches(double offX, double offY, double offZ, double tolerance) {
        return usable()
                && Math.abs(offsets.get(0) - offX) <= tolerance
                && Math.abs(offsets.get(1) - offY) <= tolerance
                && Math.abs(offsets.get(2) - offZ) <= tolerance;
    }

    /** Whether a candidate at {@code distance} blocks from the arrow's origin is inside this band. */
    public boolean contains(double distance) {
        return distance >= min && distance <= max;
    }

    /** "0-117 blocks (near)" for the debug readout. */
    public String describe() {
        return min + "-" + max + " blocks" + (label.isBlank() ? "" : " (" + label + ")");
    }
}
