/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.diana.model;

import java.util.List;
import java.util.Locale;

/**
 * One recognisable particle packet, as data: which types carry it, how many particles, at what
 * speed, spread through what box - and what it means when all of that lines up.
 *
 * <h2>Why the whole packet and not the type</h2>
 *
 * <p>The type alone is ambiguous in both directions during this event. The same critical-hit
 * particle is a mob burrow at one count and a footstep at another; the same dripping-lava particle
 * is a treasure burrow at one speed and the spade's flight trail at another. Matching the type and
 * hoping produces a detector that fires on every footstep in the Hub - which is exactly what the
 * first version of the shipped table did.
 *
 * <h2>Every field is optional, and absence means "do not care"</h2>
 *
 * <p>{@link #count}, {@link #speed} and {@link #offsets} are all nullable, because not every
 * signature is pinned on all three. The arrow's offsets are a colour payload rather than a spread
 * box, so an arrow signature deliberately leaves them unmatched; a removal signature pins the
 * offsets at zero and does not care how many particles arrived. A signature that pins nothing
 * matches on type alone, which is legal and is how a capture-in-progress entry can be written
 * before its numbers are known.
 *
 * <p><b>Absence never means zero.</b> A null count is "any count"; a count of zero is the vanilla
 * directional form and is a real, specific thing to match. Conflating them would silently turn the
 * arrow signature into a match-everything.
 *
 * <h2>Tolerances</h2>
 *
 * <p>Floats arrive as floats. The speed and the three offsets are compared with the tolerances the
 * document carries rather than with {@code ==}, because a value that reads as 0.35 in one client
 * generation reads as 0.34999999 in the next and a detector that cares is a detector that stops
 * working on an update nobody made.
 */
public final class BurrowSignature {

    /**
     * What a match means.
     *
     * <p>A string in the file rather than the enum directly, so a document naming a role this build
     * has no constant for skips that one entry instead of failing the whole table - which is what
     * keeps a newer file partly usable on an older build.
     */
    public String role;

    /**
     * The namespaced particle type ids this signature accepts, lower case.
     *
     * <p>A list rather than one id because the server runs on an older protocol and reaches this
     * client through a translation layer: the same particle can arrive under more than one modern
     * name, and carrying the aliases is cheaper than finding out that the one name we picked is the
     * one that never arrives.
     */
    public List<String> types = List.of();

    /** How many particles the packet asks for, or {@code null} to accept any count. */
    public Integer count;

    /** The packet's max-speed scalar, or {@code null} to accept any. May legitimately be negative. */
    public Double speed;

    /** The three offset floats, or {@code null} to leave them unmatched. Must be length 3 if given. */
    public List<Double> offsets;

    /** A free-text note carried into captures and debug output. Never parsed. */
    public String note = "";

    /** The role this signature resolves to, or {@code null} when the file names one we lack. */
    public SignatureRole resolvedRole() {
        if (role == null || role.isBlank()) {
            return null;
        }
        try {
            return SignatureRole.valueOf(role.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Whether this entry is complete enough to be matched at all.
     *
     * <p>A signature with no role or no type can never fire, and one carrying a role this build does
     * not know is the newer-file case above. Both are skipped rather than treated as a parse
     * failure.
     */
    public boolean usable() {
        return resolvedRole() != null && types != null && !types.isEmpty()
                && (offsets == null || offsets.size() == 3);
    }

    /**
     * Whether a packet's readings match this signature.
     *
     * <p>Called once per signature per particle packet, on a path that runs thousands of times a
     * minute in a populated Hub, so the cheapest test - the type - is first and everything else is
     * only reached by a packet that already got past it.
     *
     * @param typeId          the packet's namespaced particle type, lower case
     * @param packetCount     the packet's particle count
     * @param packetSpeed     the packet's max-speed scalar
     * @param offX            the packet's X offset
     * @param offY            the packet's Y offset
     * @param offZ            the packet's Z offset
     * @param speedTolerance  how far the speed may differ and still match
     * @param offsetTolerance how far each offset may differ and still match
     */
    public boolean matches(String typeId, int packetCount, double packetSpeed,
                           double offX, double offY, double offZ,
                           double speedTolerance, double offsetTolerance) {
        if (typeId == null || !containsType(typeId)) {
            return false;
        }
        if (count != null && count != packetCount) {
            return false;
        }
        if (speed != null && Math.abs(speed - packetSpeed) > speedTolerance) {
            return false;
        }
        if (offsets == null) {
            return true;
        }
        return near(offsets.get(0), offX, offsetTolerance)
                && near(offsets.get(1), offY, offsetTolerance)
                && near(offsets.get(2), offZ, offsetTolerance);
    }

    private boolean containsType(String typeId) {
        for (String type : types) {
            if (type != null && type.equalsIgnoreCase(typeId)) {
                return true;
            }
        }
        return false;
    }

    private static boolean near(Double expected, double actual, double tolerance) {
        return expected != null && Math.abs(expected - actual) <= tolerance;
    }

    /** One line for the debug readout and the capture file. */
    public String describe() {
        StringBuilder out = new StringBuilder(80);
        out.append(role == null ? "?" : role).append(' ').append(types);
        if (count != null) {
            out.append(" n=").append(count);
        }
        if (speed != null) {
            out.append(" spd=").append(speed);
        }
        if (offsets != null) {
            out.append(" off=").append(offsets);
        }
        return out.toString();
    }
}
