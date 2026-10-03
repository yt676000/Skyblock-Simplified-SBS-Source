/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.visual.model;

/**
 * Ambient potion-effect particle visibility.
 *
 * <ul>
 *   <li>{@link #ON} – cancel the particles completely.</li>
 *   <li>{@link #SEETHROUGH} – thin them out (a density-based approximation of ~0.2 alpha) so they
 *       don't block vision.</li>
 *   <li>{@link #OFF} – render them completely normally (vanilla).</li>
 * </ul>
 */
public enum PotionParticleMode {

    ON("On"),
    SEETHROUGH("See-through"),
    OFF("Off");

    private final String displayName;

    PotionParticleMode(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public PotionParticleMode next() {
        PotionParticleMode[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
