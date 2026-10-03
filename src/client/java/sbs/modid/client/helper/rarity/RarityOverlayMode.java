/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.rarity;

/**
 * How the item-rarity overlay is drawn over an item icon.
 *
 * <ul>
 *   <li>{@link #OFF} – no overlay.</li>
 *   <li>{@link #ROUND} – a translucent rarity-colored circle over the slot.</li>
 *   <li>{@link #SQUARE} – a translucent rarity-colored square over the slot.</li>
 * </ul>
 */
public enum RarityOverlayMode {

    OFF("Off"),
    ROUND("Round"),
    SQUARE("Square");

    private final String displayName;

    RarityOverlayMode(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** The next mode in the cycle (used by the settings toggle button). */
    public RarityOverlayMode next() {
        RarityOverlayMode[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
