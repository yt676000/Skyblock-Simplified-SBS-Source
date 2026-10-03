/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.hud.model;

/**
 * XP-bar rendering mode chosen in the Hypixel GUI module.
 *
 * <ul>
 *   <li>{@link #NONE} – leave the vanilla experience bar untouched (default).</li>
 *   <li>{@link #SBS_XP_BAR} – replace the vanilla bar with the SBS rounded XP bar.</li>
 *   <li>{@link #SBS_XP_BAR_OUTLINED} – identical to {@link #SBS_XP_BAR}, plus a subtle
 *       1-pixel darkened outline around the whole bar for better visibility.</li>
 * </ul>
 * The level number above the bar stays vanilla in every mode.
 */
public enum XpBarMode {

    NONE("Original"),
    SBS_XP_BAR("SBS"),
    SBS_XP_BAR_OUTLINED("SBS Outlined");

    private final String displayName;

    XpBarMode(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** True when the SBS XP bar should be drawn in place of the vanilla experience bar. */
    public boolean rendersBar() {
        return this != NONE;
    }

    /** True when the XP bar should carry the subtle 1-pixel darkened outline. */
    public boolean outlined() {
        return this == SBS_XP_BAR_OUTLINED;
    }

    /** The next mode in the cycle (used by the toggle button). */
    public XpBarMode next() {
        XpBarMode[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
