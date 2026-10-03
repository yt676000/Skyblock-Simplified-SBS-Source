/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.keybind;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * An optional world-area condition for a {@link CommandKeybind}: an axis-aligned box the player
 * must stand inside for the keybind to fire. A small Gson POJO stored with the config.
 *
 * <p>Captured live from the player's position: {@link #captureFromPlayer} sets both corners to the
 * current block (a point), then the editor lets it grow – but even a single-block box is useful as a
 * "here" anchor with a radius via {@link #contains}. Kept deliberately simple and self-describing.
 */
public final class AreaFilter {

    private boolean enabled = false;
    private int x1;
    private int y1;
    private int z1;
    private int x2;
    private int y2;
    private int z2;
    /** Extra blocks added around the box on every side when testing {@link #contains}. */
    private int radius = 8;

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int radius() {
        return radius;
    }

    public void setRadius(int radius) {
        this.radius = Math.max(0, radius);
    }

    /** Sets both corners to the player's current block and enables the filter. */
    public void captureFromPlayer() {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        BlockPos pos = player.blockPosition();
        x1 = x2 = pos.getX();
        y1 = y2 = pos.getY();
        z1 = z2 = pos.getZ();
        enabled = true;
    }

    /** True when {@code pos} is inside the box grown by {@link #radius} on each side. */
    public boolean contains(BlockPos pos) {
        int minX = Math.min(x1, x2) - radius;
        int maxX = Math.max(x1, x2) + radius;
        int minY = Math.min(y1, y2) - radius;
        int maxY = Math.max(y1, y2) + radius;
        int minZ = Math.min(z1, z2) - radius;
        int maxZ = Math.max(z1, z2) + radius;
        return pos.getX() >= minX && pos.getX() <= maxX
                && pos.getY() >= minY && pos.getY() <= maxY
                && pos.getZ() >= minZ && pos.getZ() <= maxZ;
    }

    /** A short "x,y,z (±r)" description for the editor, or "not set". */
    public String describe() {
        if (!enabled) {
            return "off";
        }
        int cx = (x1 + x2) / 2;
        int cy = (y1 + y2) / 2;
        int cz = (z1 + z2) / 2;
        return cx + "," + cy + "," + cz + " (±" + radius + ")";
    }
}
