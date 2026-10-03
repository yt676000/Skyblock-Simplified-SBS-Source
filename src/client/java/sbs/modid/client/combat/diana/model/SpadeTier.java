/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.diana.model;

/**
 * The Griffin spade tiers, in upgrade order.
 *
 * <p>The tier matters for exactly one thing - how long a burrow chain runs - and that mapping is
 * {@link DianaParticleData}'s to answer, not this enum's. Names match the data file's {@code tier}
 * values, so a new tier is a data edit plus one constant here.
 */
public enum SpadeTier {

    ANCESTRAL("Ancestral Spade"),
    ARCHAIC("Archaic Spade"),
    DEIFIC("Deific Spade");

    private final String displayName;

    SpadeTier(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
