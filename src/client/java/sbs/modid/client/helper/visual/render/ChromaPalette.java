/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.visual.render;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.render.OverlayColor;
import sbs.modid.client.ui.theme.SBSTheme;

/**
 * Which colours a chroma sweep travels through.
 *
 * <p>Chroma used to be the full hue circle and nothing else - the position along the sweep <i>was</i>
 * the hue. A palette breaks that assumption: the position now picks a point on a loop of colour
 * stops, and the hue circle is simply the palette that happens to have every hue on it. Everything
 * downstream is unchanged, because the ramp still answers the same question ("what colour is this
 * point of the sweep") and {@link Chroma}'s luminance correction still runs on the answer.
 *
 * <p><b>One palette drives every chroma effect at once</b> - the maxed-enchant text shimmer, the
 * active-pet bar, the profile viewer's maxed rows. That is deliberate and matches the existing rule
 * for chroma <i>speed</i>: these are meant to read as one effect appearing in several places, and
 * two of them running different colour schemes is the fastest way to lose that. Which is also why
 * this lives in the Theme module rather than next to any one of them.
 *
 * <p>Stops are interpolated in HSB along the <b>shorter way round the hue circle</b>, not in RGB.
 * An RGB blend between two saturated colours dips through grey in the middle - red to cyan would
 * literally fade out and back - and a chroma that goes grey halfway is the same defect as one that
 * goes black.
 */
public enum ChromaPalette {

    /** The original: every hue, evenly. */
    RAINBOW("Rainbow", "Every hue in order - how chroma has always looked."),

    /**
     * Built from the live theme accent, so chroma stops being an unrelated rainbow on an otherwise
     * themed client. The sweep breathes around the accent's own hue rather than leaving it.
     */
    ACCENT("Theme Accent", "Your accent colour, shimmering around its own hue."),

    FIRE("Fire", "Deep red through orange into gold.", 0xFF3B21, 0xFF8A1F, 0xFFD24D),
    OCEAN("Ocean", "Blue through cyan into aqua.", 0x2E7BFF, 0x32C8FF, 0x3FF0D0),
    AURORA("Aurora", "Green through cyan into violet.", 0x3FF0A0, 0x3FD0FF, 0xA56BFF),
    SUNSET("Sunset", "Pink through orange into gold.", 0xFF4D6D, 0xFF8A3D, 0xFFC94D),
    CANDY("Candy", "Pink, violet and ice blue.", 0xFF5FA2, 0xC86BFF, 0x5FD8FF),
    GOLD("Gold", "The legendary shimmer - dark gold to near-white.", 0xC99A2E, 0xFFD54D, 0xFFF3B0),

    /** The player's own stops, from the Theme module. */
    CUSTOM("Custom", "Your own colours, blended in the order you set them.");

    private final String displayName;
    private final String tagline;
    private final int[] stops;

    ChromaPalette(String displayName, String tagline, int... stops) {
        this.displayName = displayName;
        this.tagline = tagline;
        this.stops = stops;
    }

    public String displayName() {
        return displayName;
    }

    public String tagline() {
        return tagline;
    }

    /** Parses a persisted name, falling back to {@link #RAINBOW} for anything unknown. */
    public static ChromaPalette parse(String name) {
        if (name != null) {
            for (ChromaPalette palette : values()) {
                if (palette.name().equalsIgnoreCase(name)) {
                    return palette;
                }
            }
        }
        return RAINBOW;
    }

    /**
     * The palette currently selected in the Theme module.
     *
     * <p>Cached on the config string rather than parsed every call: this sits on the per-character
     * path of the text shimmer and the per-column path of the chroma bar, so it runs hundreds of
     * times a frame.
     */
    public static ChromaPalette active() {
        String name = ConfigManager.getInstance().get().theme.chromaPalette;
        ChromaPalette cached = activePalette;
        if (cached != null && activeName != null && activeName.equals(name)) {
            return cached;
        }
        ChromaPalette parsed = parse(name);
        activeName = name;
        activePalette = parsed;
        return parsed;
    }

    private static volatile String activeName;
    private static volatile ChromaPalette activePalette;

    /**
     * The colour at a point of the sweep, packed RGB, <b>before</b> {@link Chroma}'s luminance
     * correction. {@code turns} is a position in rainbows and wraps, so the loop is seamless: the
     * last stop blends back into the first.
     */
    public int rgbAt(double turns) {
        double t = ((turns % 1.0) + 1.0) % 1.0;
        int[] ramp = ramp();
        if (ramp.length == 0) {
            return Chroma.hsvToRgb((float) t);
        }
        if (ramp.length == 1) {
            return ramp[0];   // one stop is a solid colour, which is a legitimate thing to want
        }
        double scaled = t * ramp.length;
        int index = (int) scaled;
        return blend(ramp[index % ramp.length], ramp[(index + 1) % ramp.length],
                (float) (scaled - index));
    }

    /** This palette's stops, resolved: empty means "the full hue circle". */
    private int[] ramp() {
        return switch (this) {
            case RAINBOW -> NO_STOPS;
            case ACCENT -> accentStops();
            case CUSTOM -> customStops();
            default -> stops;
        };
    }

    private static final int[] NO_STOPS = new int[0];

    /**
     * How far either side of the accent's hue the shimmer travels, in turns. Deliberately narrow -
     * wide enough to read as movement, not so wide that "Theme Accent" arrives somewhere that is no
     * longer recognisably the accent.
     */
    private static final float ACCENT_SWING = 0.055f;

    /**
     * Rebuilt only when the accent actually changes. Everything in here is on the per-character path
     * of the text shimmer and the per-column path of the chroma bar, so an {@code int[]} built per
     * call would be a few thousand short-lived arrays a second for a gradient nobody can see move
     * any better for it.
     */
    private static int[] accentStops() {
        int accent = SBSTheme.ACCENT & 0xFFFFFF;
        int[] cached = accentCache;
        if (cached != null && accent == accentKey) {
            return cached;
        }
        float[] hsb = SBSTheme.rgbToHsb(accent);
        // Saturation and brightness are floored, not taken as-is: a nearly-white or nearly-black
        // accent has no hue worth sweeping and would come out as a flat bar.
        float sat = Math.max(0.55f, hsb[1]);
        float bri = Math.max(0.75f, hsb[2]);
        int[] built = {
                SBSTheme.hsbToRgb(hsb[0] - ACCENT_SWING, sat, bri),
                SBSTheme.hsbToRgb(hsb[0], Math.max(0.35f, sat - 0.25f), Math.min(1f, bri + 0.15f)),
                SBSTheme.hsbToRgb(hsb[0] + ACCENT_SWING, sat, bri),
        };
        accentKey = accent;
        accentCache = built;
        return built;
    }

    private static volatile int accentKey = -1;
    private static volatile int[] accentCache;

    /**
     * The player's stops, skipping blanks so the count is whatever they filled in - two colours for
     * a simple back-and-forth, four for a full loop, one for a solid colour. All blank falls back to
     * the hue circle rather than to nothing.
     */
    private static int[] customStops() {
        var theme = ConfigManager.getInstance().get().theme;
        int[] cached = customCache;
        // Compared field by field rather than against one concatenated key, for the same reason
        // accentStops caches at all: joining four strings here would allocate on every character of
        // every chroma run, every frame. The four are equal by reference until the player edits one,
        // so the check short-circuits immediately.
        if (cached != null
                && java.util.Objects.equals(key1, theme.chromaCustom1)
                && java.util.Objects.equals(key2, theme.chromaCustom2)
                && java.util.Objects.equals(key3, theme.chromaCustom3)
                && java.util.Objects.equals(key4, theme.chromaCustom4)) {
            return cached;
        }
        int[] parsed = parseStops(theme.chromaCustom1, theme.chromaCustom2,
                theme.chromaCustom3, theme.chromaCustom4);
        key1 = theme.chromaCustom1;
        key2 = theme.chromaCustom2;
        key3 = theme.chromaCustom3;
        key4 = theme.chromaCustom4;
        customCache = parsed;
        return parsed;
    }

    private static volatile String key1;
    private static volatile String key2;
    private static volatile String key3;
    private static volatile String key4;
    private static volatile int[] customCache;

    private static int[] parseStops(String... hexes) {
        int[] out = new int[hexes.length];
        int count = 0;
        for (String hex : hexes) {
            Integer rgb = OverlayColor.parseHex(hex);
            if (rgb != null) {
                out[count++] = rgb;
            }
        }
        return count == hexes.length ? out : java.util.Arrays.copyOf(out, count);
    }

    /** Blends two stops in HSB, taking the shorter way round the hue circle. */
    private static int blend(int a, int b, float f) {
        float[] ha = SBSTheme.rgbToHsb(a);
        float[] hb = SBSTheme.rgbToHsb(b);
        float delta = hb[0] - ha[0];
        if (delta > 0.5f) {
            delta -= 1f;
        } else if (delta < -0.5f) {
            delta += 1f;
        }
        // A grey stop has no meaningful hue - carry its neighbour's, or the blend swings through
        // whatever hue zero happens to be (red) on the way out of it.
        float hue = hb[1] < 0.02f ? ha[0] : (ha[1] < 0.02f ? hb[0] : ha[0] + delta * f);
        return SBSTheme.hsbToRgb(hue, ha[1] + (hb[1] - ha[1]) * f, ha[2] + (hb[2] - ha[2]) * f);
    }
}
