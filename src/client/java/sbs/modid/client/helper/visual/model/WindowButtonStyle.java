/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.visual.model;

/**
 * How the minimise / maximise / close glyphs on the Windows title bar are drawn.
 *
 * <p><b>Light or dark is the whole range Windows offers</b>, and this enum exists to say so
 * honestly. The glyphs belong to the desktop compositor, which exposes exactly one lever for them –
 * {@code DWMWA_USE_IMMERSIVE_DARK_MODE}, a single on/off flag. There is no per-window attribute for
 * their colour, so an arbitrary one would mean an undecorated window with a mod-drawn caption, and
 * with it re-implementing dragging, snapping, double-click-maximise and per-monitor DPI, all of
 * which Windows currently does for free. See
 * {@link sbs.modid.client.helper.visual.logic.WindowTitleBar}.
 *
 * <ul>
 *   <li>{@link #AUTO} – pick whichever of the two is legible on the caption colour in force.</li>
 *   <li>{@link #WHITE} – always light glyphs.</li>
 *   <li>{@link #BLACK} – always dark glyphs.</li>
 * </ul>
 */
public enum WindowButtonStyle {

    AUTO("Auto"),
    WHITE("White"),
    BLACK("Black");

    /** Rec. 709 luminance weights – the same perceptual weighting the chroma ramp uses. */
    private static final float LUMA_R = 0.2126f;
    private static final float LUMA_G = 0.7152f;
    private static final float LUMA_B = 0.0722f;

    /**
     * Caption luminance below which {@link #AUTO} goes light. Above 0.5 would flip the glyphs on
     * mid-tone captions sooner than the eye wants: white on a mid blue still reads, black on it
     * does not, so the switch sits a little above the midpoint.
     */
    private static final float LIGHT_BELOW = 0.55f;

    private final String displayName;

    WindowButtonStyle(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public WindowButtonStyle next() {
        WindowButtonStyle[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    /**
     * Whether the glyphs should be drawn light on the given caption.
     *
     * <p>{@link #AUTO} is what makes this worth a setting rather than a toggle: the caption colour
     * follows the SBS theme unless the player pinned one, so it moves with every accent change, UI
     * style switch and profile load – and glyphs fixed to white vanish the moment the theme goes
     * pale. Deciding from the colour actually in force keeps them readable through all of it.
     *
     * @param captionArgb the caption colour as {@code 0xAARRGGBB}
     */
    public boolean lightGlyphs(int captionArgb) {
        return switch (this) {
            case WHITE -> true;
            case BLACK -> false;
            case AUTO -> luminance(captionArgb) < LIGHT_BELOW;
        };
    }

    /** Perceived brightness of a colour, 0 (black) to 1 (white); alpha is ignored. */
    private static float luminance(int argb) {
        float r = ((argb >> 16) & 0xFF) / 255.0f;
        float g = ((argb >> 8) & 0xFF) / 255.0f;
        float b = (argb & 0xFF) / 255.0f;
        return LUMA_R * r + LUMA_G * g + LUMA_B * b;
    }
}
