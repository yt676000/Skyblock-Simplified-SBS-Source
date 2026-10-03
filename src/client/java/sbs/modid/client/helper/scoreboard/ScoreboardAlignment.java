/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.scoreboard;

/**
 * Which edge of the Custom Scoreboard panel a row lines up against.
 *
 * <p>The title and the body lines carry one of these each, because they want different answers far
 * more often than not: Hypixel centres its {@code SKYBLOCK} heading over a left-aligned list, and a
 * panel dragged against the right screen border usually wants both sides flipped.
 */
public enum ScoreboardAlignment {

    LEFT("Left"),
    CENTER("Center"),
    RIGHT("Right");

    private final String displayName;

    ScoreboardAlignment(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public ScoreboardAlignment next() {
        return values()[(ordinal() + 1) % values().length];
    }

    /**
     * The x a piece of text {@code textWidth} wide starts at inside a panel of {@code panelWidth}.
     *
     * <p>The padding only applies to the two edge-hugging modes - a centred row is centred in the
     * whole panel, which is what makes it sit under a centred title rather than a padding-width to
     * one side of it.
     */
    public int startX(int panelX, int panelWidth, int padding, int textWidth) {
        return switch (this) {
            case LEFT -> panelX + padding;
            case CENTER -> panelX + (panelWidth - textWidth) / 2;
            case RIGHT -> panelX + panelWidth - padding - textWidth;
        };
    }

    /** The value, or {@code fallback} when Gson read a name this enum no longer has ({@code null}). */
    public static ScoreboardAlignment orDefault(ScoreboardAlignment value, ScoreboardAlignment fallback) {
        return value == null ? fallback : value;
    }
}
