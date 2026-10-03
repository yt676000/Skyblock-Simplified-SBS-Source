/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.build.model;

import java.util.Locale;

/**
 * The six block directions, as the commands name them, independent of the game's own
 * {@code Direction} so the selection maths stays testable.
 */
public enum BuildDir {
    UP(0, 1, 0),
    DOWN(0, -1, 0),
    NORTH(0, 0, -1),
    SOUTH(0, 0, 1),
    EAST(1, 0, 0),
    WEST(-1, 0, 0);

    public final int dx;
    public final int dy;
    public final int dz;

    BuildDir(int dx, int dy, int dz) {
        this.dx = dx;
        this.dy = dy;
        this.dz = dz;
    }

    public BuildDir opposite() {
        return switch (this) {
            case UP -> DOWN;
            case DOWN -> UP;
            case NORTH -> SOUTH;
            case SOUTH -> NORTH;
            case EAST -> WEST;
            case WEST -> EAST;
        };
    }

    /** True for the three directions that point along a positive axis. */
    public boolean positive() {
        return dx + dy + dz > 0;
    }

    /**
     * A direction from a command word: the full name, its first letter, or {@code null} when the word
     * is not a direction (the caller then falls back to where the player looks).
     */
    public static BuildDir parse(String word) {
        if (word == null) {
            return null;
        }
        return switch (word.trim().toLowerCase(Locale.ROOT)) {
            case "up", "u" -> UP;
            case "down", "d" -> DOWN;
            case "north", "n" -> NORTH;
            case "south", "s" -> SOUTH;
            case "east", "e" -> EAST;
            case "west", "w" -> WEST;
            default -> null;
        };
    }

    /**
     * The direction a player faces: straight up or down past 67.5 degrees of pitch, otherwise the
     * horizontal quarter the yaw falls in. Yaw follows the game: 0 = south, 90 = west, 180 = north,
     * 270 = east; pitch is positive looking down.
     */
    public static BuildDir fromLook(float yaw, float pitch) {
        if (pitch > 67.5f) {
            return DOWN;
        }
        if (pitch < -67.5f) {
            return UP;
        }
        return horizontalFromYaw(yaw);
    }

    /** The horizontal quarter a yaw falls in, ignoring pitch. */
    public static BuildDir horizontalFromYaw(float yaw) {
        int quarter = Math.floorMod((int) Math.floor(yaw / 90.0 + 0.5), 4);
        return switch (quarter) {
            case 0 -> SOUTH;
            case 1 -> WEST;
            case 2 -> NORTH;
            default -> EAST;
        };
    }

    /** Clockwise quarter turn about Y (north to east); up and down stay. */
    public BuildDir clockwise() {
        return switch (this) {
            case NORTH -> EAST;
            case EAST -> SOUTH;
            case SOUTH -> WEST;
            case WEST -> NORTH;
            default -> this;
        };
    }

    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }
}
