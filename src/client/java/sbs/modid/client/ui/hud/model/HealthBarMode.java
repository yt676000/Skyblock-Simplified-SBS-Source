/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.hud.model;

/**
 * Health rendering mode chosen in the Hypixel GUI module.
 *
 * <ul>
 *   <li>{@link #CLASSIC_HEARTS} – leave vanilla heart rendering untouched (default).</li>
 *   <li>{@link #ROUNDED_BAR} – hide the hearts and draw the SBS rounded health bar instead.</li>
 *   <li>{@link #ROUNDED_BAR_OUTLINED} – identical to {@link #ROUNDED_BAR}, plus a subtle
 *       1-pixel darkened outline around the whole bar for better visibility.</li>
 * </ul>
 */
public enum HealthBarMode {

    CLASSIC_HEARTS("Original"),
    ROUNDED_BAR("SBS"),
    ROUNDED_BAR_OUTLINED("SBS Outlined");

    private final String displayName;

    HealthBarMode(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** True when the SBS rounded bar should be drawn in place of the vanilla hearts. */
    public boolean rendersBar() {
        return this != CLASSIC_HEARTS;
    }

    /** True when the rounded bar should carry the subtle 1-pixel darkened outline. */
    public boolean outlined() {
        return this == ROUNDED_BAR_OUTLINED;
    }

    /** The next mode in the cycle (used by the toggle button). */
    public HealthBarMode next() {
        HealthBarMode[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
