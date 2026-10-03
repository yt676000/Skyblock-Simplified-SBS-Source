/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.visual.model;

/**
 * First-person fire overlay behaviour.
 *
 * <ul>
 *   <li>{@link #DEFAULT} – vanilla full-screen fire.</li>
 *   <li>{@link #LOWERED} – shrink / shift the fire to the bottom of the screen for central vision.</li>
 *   <li>{@link #OFF} – don't render the fire overlay at all.</li>
 * </ul>
 */
public enum FireOverlayMode {

    DEFAULT("Default"),
    LOWERED("Lowered"),
    OFF("Off");

    private final String displayName;

    FireOverlayMode(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public FireOverlayMode next() {
        FireOverlayMode[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
