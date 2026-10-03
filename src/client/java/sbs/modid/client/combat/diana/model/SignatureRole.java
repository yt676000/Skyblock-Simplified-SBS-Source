/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.diana.model;

/**
 * What one recognised particle packet <i>means</i> during the Mythological Ritual.
 *
 * <h2>Why this is not {@link BurrowKind}</h2>
 *
 * <p>Three of these roles name a burrow's kind and five do not. A packet can say "a burrow exists
 * here without saying which kind" ({@link #MARKER}, {@link #FOOTSTEP}), "the burrow that was here is
 * gone" ({@link #REMOVED}), or belong to a guess rather than to a burrow at all
 * ({@link #SPADE_TRAIL}, {@link #ARROW}). Folding those into {@code BurrowKind} would mean a kind
 * enum with members that are not kinds, and every consumer switching on it would have to remember
 * which members are lies.
 *
 * <h2>The two collisions this enum exists to survive</h2>
 *
 * <p>The particle <b>type</b> alone identifies none of these, in either direction, and both
 * collisions are live:
 *
 * <ul>
 *   <li>the critical-hit particle is {@link #MOB} at one count and {@link #FOOTSTEP} at another;</li>
 *   <li>the dripping-lava particle is {@link #TREASURE} at one speed and {@link #SPADE_TRAIL} at
 *       another.</li>
 * </ul>
 *
 * <p>A detector keyed on the type would therefore mark every footstep in the Hub as a burrow and
 * fold the spade guess into the treasure marker. Which is why {@link BurrowSignature} matches the
 * whole packet and why this enum is what it resolves to.
 */
public enum SignatureRole {

    /**
     * A burrow is at this position. Says nothing about what kind, and draws nothing on its own -
     * it upgrades a position to "known to exist" and waits for one of the three kind roles.
     */
    MARKER(null),

    /** The trail leading to a burrow. Same standing as {@link #MARKER}: presence, not kind. */
    FOOTSTEP(null),

    /** This burrow is the head of a chain. */
    START(BurrowKind.START),

    /** Digging this burrow spawns a mythological creature. */
    MOB(BurrowKind.MOB),

    /** Digging this burrow pays out. */
    TREASURE(BurrowKind.TREASURE),

    /**
     * The burrow at this position no longer exists.
     *
     * <p>Authoritative, and the only signal that is: everything at that block goes, guesses
     * included, and the block is refused for a moment afterwards so particles still in flight for
     * the burrow that just vanished cannot immediately recreate it.
     */
    REMOVED(null),

    /**
     * One point of the arc the spade's ability draws. Collected, never drawn - the guess is what
     * comes out of fitting the whole trail.
     */
    SPADE_TRAIL(null),

    /**
     * One particle of the arrow the server draws from a dug burrow toward the next one.
     *
     * <p>The offsets on these packets are <b>not</b> a velocity: Hypixel uses them as a colour
     * payload, and the colour is a distance band. A signature for this role therefore matches the
     * type, the count and the speed and deliberately leaves the offsets unmatched, because the
     * offsets are the message rather than part of the address.
     */
    ARROW(null);

    private final BurrowKind kind;

    SignatureRole(BurrowKind kind) {
        this.kind = kind;
    }

    /** The burrow kind this role names, or {@code null} for the five roles that name none. */
    public BurrowKind kind() {
        return kind;
    }

    /** Whether this role identifies a burrow's kind - i.e. whether {@link #kind()} is non-null. */
    public boolean namesKind() {
        return kind != null;
    }
}
