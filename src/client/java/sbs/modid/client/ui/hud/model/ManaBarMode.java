/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.hud.model;

/**
 * Mana rendering mode chosen in the Hypixel GUI module.
 *
 * <ul>
 *   <li>{@link #NONE} – leave the vanilla hunger bar untouched (default).</li>
 *   <li>{@link #SBS_MANA_BAR} – replace only the hunger bar with the SBS mana bar.</li>
 *   <li>{@link #SBS_MANA_BAR_OUTLINED} – identical to {@link #SBS_MANA_BAR}, plus a subtle
 *       1-pixel darkened outline around the whole bar for better visibility.</li>
 * </ul>
 */
public enum ManaBarMode {

    NONE("Original"),
    SBS_MANA_BAR("SBS"),
    SBS_MANA_BAR_OUTLINED("SBS Outlined");

    private final String displayName;

    ManaBarMode(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** True when the SBS mana bar should be drawn in place of the vanilla hunger bar. */
    public boolean rendersBar() {
        return this != NONE;
    }

    /** True when the mana bar should carry the subtle 1-pixel darkened outline. */
    public boolean outlined() {
        return this == SBS_MANA_BAR_OUTLINED;
    }

    /** The next mode in the cycle (used by the toggle button). */
    public ManaBarMode next() {
        ManaBarMode[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
