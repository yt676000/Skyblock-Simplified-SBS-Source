/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.theme;

/**
 * The colours of the shared action-mark language ({@link sbs.modid.client.ui.render.ActionMarks}),
 * derived once from a set of theme tokens. Pure arithmetic with no Minecraft types, so the
 * derivation is unit-testable; the renderer keeps one instance and re-derives it only when
 * {@link SBSTheme#revision()} moves.
 *
 * <p>Colour is never the only carrier of meaning in this language - each kind also has its own
 * shape (filled, hollow, badge position) - so the palette may follow the theme freely. The two
 * semantic hues ({@link #DO_RGB}, {@link #ALT_RGB}) are fixed like {@link SBSTheme#TOGGLE_ON}: a
 * retinted "click this" that drifts towards the panel colour would stop standing out.
 */
public final class MarkPalette {

    /** "Do this" - the same green as the ON pill, so the mod has one colour for "yes". */
    public static final int DO_RGB = 0x57D977;
    /** "Do this, the other way" (right-click) - amber, far from both green and the blue accent. */
    public static final int ALT_RGB = 0xFFB347;

    public final int doFill;
    public final int doEdge;
    public final int doGlow;
    public final int altFill;
    public final int altEdge;
    public final int altGlow;
    /** Hollow ring for "next / not yet", in the theme accent. */
    public final int nextRing;
    /** Band behind a column or row the player should watch (melody's target column). */
    public final int band;
    public final int badgeFill;
    public final int badgeText;
    /** Veil over a cell that is already done; translucent so the cell keeps its colour. */
    public final int doneVeil;
    public final int doneCheck;
    /** Screen dim behind a board: light enough that the world stays partly visible. */
    public final int backdrop;
    public final int cellEmpty;
    public final int cellItem;

    private MarkPalette(int accent, int panel, int text) {
        doFill = argb(0xC0, DO_RGB);
        doEdge = argb(0xFF, lighten(DO_RGB, 0.35f));
        doGlow = argb(0x40, DO_RGB);
        altFill = argb(0xC0, ALT_RGB);
        altEdge = argb(0xFF, lighten(ALT_RGB, 0.35f));
        altGlow = argb(0x40, ALT_RGB);
        nextRing = argb(0xE0, accent);
        band = argb(0x38, accent);
        badgeFill = argb(0xF0, darken(panel, 0.5f));
        badgeText = argb(0xFF, text);
        doneVeil = argb(0x90, darken(panel, 0.6f));
        doneCheck = argb(0xFF, DO_RGB);
        backdrop = argb(0x88, darken(panel, 0.6f));
        cellEmpty = argb(0x40, panel);
        cellItem = argb(0x90, panel);
    }

    /**
     * @param accentArgb the theme accent ({@link SBSTheme#ACCENT})
     * @param panelArgb  the panel base ({@link SBSTheme#PANEL_BASE})
     * @param textArgb   body text ({@link SBSTheme#TEXT})
     */
    public static MarkPalette derive(int accentArgb, int panelArgb, int textArgb) {
        return new MarkPalette(accentArgb & 0xFFFFFF, panelArgb & 0xFFFFFF, textArgb & 0xFFFFFF);
    }

    /**
     * {@code argb} with its alpha scaled by {@code factor} (0..1). The pulse uses this per frame, so
     * it is plain integer arithmetic.
     */
    public static int scaleAlpha(int argb, float factor) {
        float f = Math.max(0f, Math.min(1f, factor));
        int alpha = Math.round(((argb >>> 24) & 0xFF) * f);
        return (alpha << 24) | (argb & 0xFFFFFF);
    }

    /**
     * A 0..1 triangle wave with the given period - the "now" pulse. Never fully 0, so a pulsing mark
     * is never invisible at the instant the player looks.
     */
    public static float pulse(long millis, int periodMs) {
        if (periodMs <= 0) {
            return 1f;
        }
        long phase = Math.floorMod(millis, (long) periodMs);
        float t = phase / (float) periodMs;
        float tri = t < 0.5f ? t * 2f : (1f - t) * 2f;
        return 0.35f + 0.65f * tri;
    }

    static int argb(int alpha, int rgb) {
        return (alpha & 0xFF) << 24 | (rgb & 0xFFFFFF);
    }

    static int lighten(int rgb, float amount) {
        return mix(rgb, 0xFFFFFF, amount);
    }

    static int darken(int rgb, float amount) {
        return mix(rgb, 0x000000, amount);
    }

    private static int mix(int rgb, int toward, float amount) {
        int r = channel(rgb >> 16, toward >> 16, amount);
        int g = channel(rgb >> 8, toward >> 8, amount);
        int b = channel(rgb, toward, amount);
        return r << 16 | g << 8 | b;
    }

    private static int channel(int from, int to, float amount) {
        int a = from & 0xFF;
        int b = to & 0xFF;
        return Math.round(a + (b - a) * amount) & 0xFF;
    }
}
