/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.hunting.model;

import net.minecraft.core.BlockPos;

/**
 * The known Gemzie spawn spots inside the Critter Safari: where they are, what they are called and
 * the colour each ships in.
 *
 * <p><b>This enum is the only place a spawn spot exists.</b> The settings rows are generated from it
 * and the publisher loops over it, so adding a fourth spot is one constant here and nothing else -
 * no new config field, no new row, no edit to the settings page. That is the same property the
 * Galatea preset page is built around, and hard-coding one spot's row anywhere would quietly destroy
 * it.
 *
 * <p><b>{@link #id()} is the config key and is assigned once.</b> Never derive it from the label and
 * never renumber it: the player's colour and opacity are stored against it, so a reused id silently
 * moves their correction onto a different spot. The label is display text and may change freely.
 *
 * <p><b>Opacity is a percentage, not an eighth hex digit.</b> The shipped colour picker parses
 * {@code RRGGBB} only ({@code OverlayColor.parseHex} rejects every other length outright), so an
 * {@code AARRGGBB} value would silently read as "no colour set". The requested alpha therefore lives
 * in {@link #defaultOpacity()}: {@code 0x85} is 133, which is 52 % of 255, and converting 52 % back
 * with round-half-up returns exactly 133 again.
 *
 * <p><b>The coordinates have not been verified in game</b> - they were supplied in the request and
 * appear in no dataset here. That is why the feature ships off; see
 * {@code docs/features/gemzie-waypoints.md}.
 */
public enum GemzieSpot {

    /** {@code 0xFF00FF00} - green, fully opaque. */
    ONE("gemzie_1", "Gemzie 1", 137, 61, 51, 0x00FF00, 100),

    /** {@code 0xFFFF00C8} - purple, fully opaque. */
    TWO("gemzie_2", "Gemzie 2", 140, 61, 56, 0xFF00C8, 100),

    /** {@code 0x85FF9700} - orange, and semi-transparent on purpose. */
    THREE("gemzie_3", "Gemzie 3", 140, 61, 46, 0xFF9700, 52);

    private final String id;
    private final String label;
    private final int x;
    private final int y;
    private final int z;
    private final int defaultRgb;
    private final int defaultOpacity;

    GemzieSpot(String id, String label, int x, int y, int z, int defaultRgb, int defaultOpacity) {
        this.id = id;
        this.label = label;
        this.x = x;
        this.y = y;
        this.z = z;
        this.defaultRgb = defaultRgb;
        this.defaultOpacity = defaultOpacity;
    }

    /** Stable config key. Assigned once; never renamed and never reused. */
    public String id() {
        return id;
    }

    /** What the world label and the settings rows call this spot. */
    public String label() {
        return label;
    }

    public BlockPos pos() {
        return new BlockPos(x, y, z);
    }

    /** The shipped colour as {@code 0xRRGGBB} - the swatch fallback and the picker's start point. */
    public int defaultRgb() {
        return defaultRgb;
    }

    /** The shipped colour as {@code RRGGBB}, which is the form the config stores. */
    public String defaultColorHex() {
        return String.format(java.util.Locale.ROOT, "%06X", defaultRgb & 0xFFFFFF);
    }

    /** The shipped opacity as a percentage of the alpha the renderer would otherwise use. */
    public int defaultOpacity() {
        return defaultOpacity;
    }

    /** The spot with this id, or {@code null} - for reading a config key back. */
    public static GemzieSpot byId(String id) {
        if (id == null) {
            return null;
        }
        for (GemzieSpot spot : values()) {
            if (spot.id.equals(id)) {
                return spot;
            }
        }
        return null;
    }
}
