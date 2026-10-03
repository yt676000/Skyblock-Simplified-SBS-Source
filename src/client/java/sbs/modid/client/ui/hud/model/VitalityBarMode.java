/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.hud.model;

/**
 * Vitality rendering mode chosen in the Hypixel GUI module.
 *
 * <p>Vitality is Hypixel's new "healing pool" stat (like mana, but spent on healing). Unlike the
 * health and mana bars it has no vanilla HUD element to replace, so it is drawn as an extra bar
 * sitting directly above the health bar, gated purely on this mode.
 *
 * <ul>
 *   <li>{@link #NONE} – do not draw a vitality bar (default).</li>
 *   <li>{@link #SBS_VITALITY_BAR} – draw the SBS vitality bar above the health bar.</li>
 *   <li>{@link #SBS_VITALITY_BAR_OUTLINED} – identical, plus the subtle 1-pixel darkened outline.</li>
 * </ul>
 */
public enum VitalityBarMode {

    NONE("Off"),
    SBS_VITALITY_BAR("SBS"),
    SBS_VITALITY_BAR_OUTLINED("SBS Outlined");

    private final String displayName;

    VitalityBarMode(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** True when the SBS vitality bar should be drawn above the health bar. */
    public boolean rendersBar() {
        return this != NONE;
    }

    /** True when the vitality bar should carry the subtle 1-pixel darkened outline. */
    public boolean outlined() {
        return this == SBS_VITALITY_BAR_OUTLINED;
    }

    /** The next mode in the cycle (used by the toggle button). */
    public VitalityBarMode next() {
        VitalityBarMode[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
