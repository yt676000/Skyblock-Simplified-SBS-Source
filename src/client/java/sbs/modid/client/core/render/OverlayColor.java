/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.render;

/**
 * The shared colour palette for configurable overlays (Ether Warp highlight, pathfinding waypoints
 * and paths, ...), offered as a cycled preset list because the SBS settings UI has no colour picker
 * and named presets stay readable in a row.
 *
 * <p>Values are plain RGB; alpha comes from whatever opacity setting the feature has.
 */
public enum OverlayColor {

    BLUE("Blue", 0x3FB4FF),
    CYAN("Cyan", 0x00E5FF),
    GREEN("Green", 0x30E030),
    YELLOW("Yellow", 0xFFE000),
    ORANGE("Orange", 0xFF9A2E),
    RED("Red", 0xFF2020),
    PURPLE("Purple", 0xB44DFF),
    PINK("Pink", 0xFF5FC8),
    WHITE("White", 0xFFFFFF);

    private final String displayName;
    private final int rgb;

    OverlayColor(String displayName, int rgb) {
        this.displayName = displayName;
        this.rgb = rgb;
    }

    public String displayName() {
        return displayName;
    }

    /** The colour as 0xRRGGBB (no alpha). */
    public int rgb() {
        return rgb;
    }

    /** The colour as opaque ARGB. */
    public int argb() {
        return 0xFF000000 | rgb;
    }

    /** The colour with the given alpha percentage (0-100) applied. */
    public int withOpacity(int percent) {
        int pct = Math.max(0, Math.min(100, percent));
        return ((pct * 255 / 100) << 24) | rgb;
    }

    public OverlayColor next() {
        OverlayColor[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    /**
     * Parses a free-form {@code RRGGBB} colour, tolerating a leading {@code #} or {@code 0x} and
     * surrounding whitespace, or returns {@code null} when the text is empty or not a valid colour.
     *
     * <p>Returning {@code null} rather than a fallback colour is deliberate: it lets the caller tell
     * "the user has not set a custom colour" apart from "the user set black", so a half-typed hex in
     * a text field cannot silently blank an overlay out.
     */
    public static Integer parseHex(String text) {
        if (text == null) {
            return null;
        }
        String cleaned = text.trim();
        if (cleaned.startsWith("#")) {
            cleaned = cleaned.substring(1);
        } else if (cleaned.length() > 2 && (cleaned.startsWith("0x") || cleaned.startsWith("0X"))) {
            cleaned = cleaned.substring(2);
        }
        if (cleaned.length() != 6) {
            return null;
        }
        try {
            return Integer.parseInt(cleaned, 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Formats an RGB value back as {@code RRGGBB}, for showing the current colour in a text field. */
    public static String toHex(int rgb) {
        return String.format("%06X", rgb & 0xFFFFFF);
    }
}
