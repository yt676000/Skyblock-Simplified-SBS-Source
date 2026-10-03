/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.terrain;

/**
 * How aggressively the Far Terrain module works.
 *
 * <ul>
 *   <li>{@link #OFF} – nothing is captured, kept or re-served.</li>
 *   <li>{@link #PERFORMANCE} – terrain radius capped at {@value FarTerrainManager#PERFORMANCE_RADIUS}
 *       chunks and chunks stream back in slowly, so weaker machines never feel the extra mesh
 *       building.</li>
 *   <li>{@link #ON} – the full configured radius, streamed back in as fast as the renderer
 *       comfortably absorbs.</li>
 * </ul>
 */
public enum FarTerrainMode {

    OFF("Off"),
    PERFORMANCE("Performance"),
    ON("On");

    private final String displayName;

    FarTerrainMode(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public FarTerrainMode next() {
        FarTerrainMode[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
