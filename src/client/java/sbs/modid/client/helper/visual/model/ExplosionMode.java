/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.visual.model;

/**
 * Explosion particle visibility.
 *
 * <ul>
 *   <li>{@link #ON} – vanilla explosion particles.</li>
 *   <li>{@link #HALF} – thin them out (a density-based approximation of ~0.2 alpha) for clearer vision.</li>
 *   <li>{@link #OFF} – don't spawn explosion particles at all.</li>
 * </ul>
 */
public enum ExplosionMode {

    ON("On"),
    HALF("Half"),
    OFF("Off");

    private final String displayName;

    ExplosionMode(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public ExplosionMode next() {
        ExplosionMode[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
