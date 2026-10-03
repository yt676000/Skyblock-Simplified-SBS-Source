/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.visual.render;

/**
 * Shared maths for the animated rainbow ("chroma") effects: the maxed-enchantment glint in item
 * tooltips and the maxed skill/slayer rows in the profile viewer.
 *
 * <p>Both effects used to hardcode their own rate (a {@code 0.0006} cycles/ms factor here, a
 * {@code 1600}ms cycle there). They are centralised here so a single speed setting can drive them
 * and so the HSV conversion exists once.
 *
 * <p>Speed is expressed in <b>percent of the default</b>: 100 keeps the historical ~1.6s per full
 * rainbow, lower values slow the animation down, higher values speed it up. The phase always comes
 * from the wall clock, so every element on screen stays in sync.
 *
 * <p>The time phase is reduced modulo the cycle length in <b>long arithmetic</b> before it ever
 * becomes a float. Dividing raw epoch millis (~1.8e12) as a float lets a 24-bit mantissa swallow
 * the frame-to-frame delta, which freezes the animation on a single colour – a bug this codebase
 * has already hit once (see {@code EnchantTooltipMixin}).
 */
public final class Chroma {

    /** Full-rainbow cycle length at 100% speed. */
    private static final long BASE_CYCLE_MS = 1600L;

    /** Bounds of the configurable speed, in percent. */
    public static final int MIN_SPEED = 10;
    public static final int MAX_SPEED = 400;

    private Chroma() {
    }

    /** Cycle length in ms for a speed percentage (higher percent = shorter cycle = faster). */
    public static long cycleMs(int speedPercent) {
        int pct = Math.max(MIN_SPEED, Math.min(MAX_SPEED, speedPercent));
        return Math.max(50L, BASE_CYCLE_MS * 100L / pct);
    }

    /**
     * The configured speed for every chroma effect, from the Theme module.
     *
     * <p>There is one, not one per effect. The phase is taken from the wall clock, so effects that
     * share a speed are in step wherever they appear together - a shimmering tooltip over a
     * shimmering HUD card reads as one thing happening, and two speeds make it read as two.
     */
    public static int speed() {
        int configured = sbs.modid.client.core.config.ConfigManager.getInstance()
                .get().theme.chromaSpeed;
        return Math.max(MIN_SPEED, Math.min(MAX_SPEED, configured));
    }

    /** The current animation phase in {@code [0,1)} at the given speed. */
    public static double phase(int speedPercent) {
        long cycle = cycleMs(speedPercent);
        return (System.currentTimeMillis() % cycle) / (double) cycle;
    }

    /**
     * Packed opaque ARGB for a point at {@code offset} (in rainbow turns) along the sweep: the hue
     * is the offset minus the running time phase, so the wave visibly travels.
     *
     * <p>Goes through {@link #vividRgb}, not the raw HSV value it used to use - that is where the
     * "dark spots" in the active-pet bar and the maxed profile rows came from, since at full value
     * pure blue carries roughly 0.07 relative luminance against yellow's 0.93 and the blue-violet
     * stretch of the sweep rendered as near-black patches travelling along an otherwise bright bar.
     * Not {@link #evenRgb} either: a filled bar <i>is</i> the colour, so it cannot afford the
     * saturation that pinning every hue to one luminance costs.
     */
    public static int color(double offset, int speedPercent) {
        return 0xFF000000 | vividRgb(rampRgb(offset - phase(speedPercent)));
    }

    /**
     * The selected palette's colour at a point of the sweep, before any luminance correction.
     *
     * <p>The one place the palette is consulted, so every chroma effect in the mod picks up a
     * change to it without knowing palettes exist.
     */
    public static int rampRgb(double turns) {
        return ChromaPalette.active().rgbAt(turns);
    }

    /**
     * Luminance band {@link #vividRgb} keeps every hue inside. A band rather than the single value
     * {@link #evenRgb} uses, because the two corrections do not cost the same thing:
     *
     * <ul>
     *   <li><b>Pulling a bright hue down is free.</b> Scaling yellow's channels darkens it without
     *       touching its saturation at all, so the ceiling can be as low as it likes.
     *   <li><b>Pushing a dark hue up is not.</b> Pure blue at full value is already as bright as
     *       blue gets in sRGB - the only way to raise its luminance is to mix white in, which is
     *       exactly the saturation loss the rainbow was complaining about. So the floor has to be
     *       the lowest one that still clears a dark panel, not a matching pair for the ceiling.
     * </ul>
     *
     * <p>Anything already inside the band is left alone. That is the whole difference: the flat
     * ramp lifted blue all the way to 0.62 and turned it into periwinkle, this one lifts it just far
     * enough to stop being a hole in the bar.
     *
     * <p>The ceiling is <b>pure green's own luminance</b>, so green and cyan pass through untouched
     * at full brightness and yellow - the one real outlier at 0.93 - is the only hue pulled down.
     * Set it much lower and the whole rainbow goes olive and dull, which is the other way to get a
     * "weak" palette.
     */
    private static final float BAND_FLOOR = 0.30f;
    private static final float BAND_CEIL = 0.72f;

    /**
     * A hue kept <b>as saturated as sRGB allows</b> while still staying inside a readable luminance
     * band - the ramp for anything drawn as a solid fill (the maxed pet bar, the profile viewer's
     * maxed rows), as opposed to {@link #evenRgb}, which is the ramp for text.
     *
     * <p>Text and fills genuinely want different things here. Text has to stay legible at every
     * point of the sweep, so a constant luminance is worth the wash-out; a bar is judged on whether
     * it looks like a rainbow, and a rainbow that has been flattened to one brightness reads as
     * pastel. Both still fix the original defect - no part of the sweep goes near black.
     */
    public static int vividRgb(int base) {
        float r = ((base >> 16) & 0xFF) / 255.0f;
        float g = ((base >> 8) & 0xFF) / 255.0f;
        float b = (base & 0xFF) / 255.0f;

        float luma = LUMA_R * r + LUMA_G * g + LUMA_B * b;
        if (luma > BAND_CEIL) {
            // Free: the hue keeps every bit of its saturation, it just stops glaring.
            float scale = BAND_CEIL / luma;
            r *= scale;
            g *= scale;
            b *= scale;
        } else if (luma < BAND_FLOOR) {
            // Costs saturation, so it is done by the smallest amount that reaches the floor.
            float t = (BAND_FLOOR - luma) / (1.0f - luma);
            r += (1.0f - r) * t;
            g += (1.0f - g) * t;
            b += (1.0f - b) * t;
        }
        return (clamp255(r) << 16) | (clamp255(g) << 8) | clamp255(b);
    }

    /**
     * Perceived (Rec. 709) luminance the {@link #evenRgb} ramp holds every hue to. Chosen to sit
     * clearly above a dark tooltip background while still leaving the saturated hues room to stay
     * saturated - pushed much higher, every colour has to be blended toward white to reach it and
     * the rainbow washes out.
     */
    private static final float TARGET_LUMA = 0.62f;

    private static final float LUMA_R = 0.2126f;
    private static final float LUMA_G = 0.7152f;
    private static final float LUMA_B = 0.0722f;

    /**
     * A hue at <b>constant perceived brightness</b>, packed RGB (no alpha).
     *
     * <p>This is the difference between a rainbow that reads as one moving colour and one that
     * visibly throbs. Raw HSV at full value is nowhere near perceptually even: pure yellow carries
     * roughly 0.93 relative luminance and pure blue roughly 0.07, an order of magnitude apart, so a
     * scrolling HSV rainbow pulses bright-dark-bright and its blue phase all but vanishes against a
     * dark tooltip. Here the hue is generated at full value and then moved onto a fixed luminance:
     * hues brighter than the target are scaled down, hues darker than it are blended toward white
     * (the only way to raise luminance without leaving the hue), which costs some saturation on
     * blue and violet exactly where it is needed for legibility.
     *
     * @param base       the palette's colour at this point of the sweep, packed RGB
     * @param saturation 0-1; 1 is the colour as the palette gave it, lower mixes in white before
     *                   the luma correction
     */
    public static int evenRgb(int base, float saturation) {
        float sat = Math.max(0.0f, Math.min(1.0f, saturation));
        float r = (((base >> 16) & 0xFF) / 255.0f - 1.0f) * sat + 1.0f;
        float g = (((base >> 8) & 0xFF) / 255.0f - 1.0f) * sat + 1.0f;
        float b = ((base & 0xFF) / 255.0f - 1.0f) * sat + 1.0f;

        float luma = LUMA_R * r + LUMA_G * g + LUMA_B * b;
        if (luma <= 0.0001f) {
            r = g = b = TARGET_LUMA;
        } else if (luma < TARGET_LUMA) {
            // Blend toward white by exactly the amount that lands on the target luminance.
            float t = (TARGET_LUMA - luma) / (1.0f - luma);
            r += (1.0f - r) * t;
            g += (1.0f - g) * t;
            b += (1.0f - b) * t;
        } else {
            float scale = TARGET_LUMA / luma;
            r *= scale;
            g *= scale;
            b *= scale;
        }
        return (clamp255(r) << 16) | (clamp255(g) << 8) | clamp255(b);
    }

    private static int clamp255(float value) {
        return Math.max(0, Math.min(255, Math.round(value * 255.0f)));
    }

    /** Full-saturation, full-value HSV → packed RGB (no alpha). */
    public static int hsvToRgb(float hue) {
        int i = (int) (hue * 6.0f) % 6;
        float f = hue * 6.0f - (float) Math.floor(hue * 6.0f);
        int v = 255;
        int q = (int) (255 * (1.0f - f));
        int t = (int) (255 * f);
        return switch (i) {
            case 0 -> (v << 16) | (t << 8);
            case 1 -> (q << 16) | (v << 8);
            case 2 -> (v << 8) | t;
            case 3 -> (q << 8) | v;
            case 4 -> (t << 16) | v;
            default -> (v << 16) | q;
        };
    }
}
