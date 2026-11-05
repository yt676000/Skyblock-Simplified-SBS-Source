/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.slayer.model;

/**
 * The stretch of an Inferno fight you are in. {@link #NONE} is "no boss of yours is up"; the rest
 * follow the boss splitting into its demons and pulling itself back together.
 */
public enum InfernoPhase {
    NONE(""),
    BOSS("Boss"),
    DEMONS("Demons");

    private final String label;

    InfernoPhase(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
