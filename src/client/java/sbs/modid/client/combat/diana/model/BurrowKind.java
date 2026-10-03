/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.diana.model;

/**
 * What a burrow holds, which is the thing worth knowing before walking to one.
 *
 * <p>The distinction is not cosmetic: a treasure burrow is a pickup, a mob burrow is a fight you
 * have to win before the chain continues, and a start burrow is the head of a chain you may not
 * want to open while three others are already running.
 *
 * <p>Hypixel encodes this as the <b>particle type</b> hovering over the burrow rather than as a
 * colour channel - the "blue / white / orange" everyone describes is those particles' own
 * appearance. Which type means which is data, not code: see
 * {@code assets/…/diana/particles.json} and {@link DianaParticleData}.
 */
public enum BurrowKind {

    /** The head of a chain. */
    START("Start"),

    /** Digging it spawns a mythological creature; the chain continues only once it is dealt with. */
    MOB("Mob"),

    /** Digging it yields loot. */
    TREASURE("Treasure");

    private final String displayName;

    BurrowKind(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
