/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.hud.edit.model;

/**
 * Persisted per-element HUD transform: a screen-space offset from the element's default position,
 * a uniform scale factor and three opacities. Stored (keyed by {@link HudElement#id()}) in the SBS
 * config, so it is a plain mutable POJO that Gson can (de)serialize directly.
 *
 * <p>An all-defaults transform ({@code x=0, y=0, scale=1}, every opacity {@code 1}) means "draw
 * exactly where and how vanilla / SBS normally would", so unset elements cost nothing.
 *
 * <h2>Three opacities, not one</h2>
 *
 * <p>An element is drawn from three kinds of geometry and each fades on its own dial: the panel
 * {@link #opacity body}, its {@link #outlineOpacity frame} (outline and glow) and its
 * {@link #textOpacity text and icons}. Splitting them is the whole point — "let me see through my
 * HUD" almost always means softening the plate behind the numbers rather than the numbers, and the
 * reverse (a solid card whose readout is dimmed to a hint) is equally a thing people set up.
 * {@link sbs.modid.client.ui.hud.edit.logic.HudOpacity} decides which drawing lands in which bucket.
 *
 * <p>Layouts written before the split carry a single {@code opacity} plus a {@code opacityScope}
 * naming what it applied to; {@link #materialize()} turns that pair into the three values, exactly
 * preserving what each old scope did. Layouts predating opacity entirely have neither key and land
 * on the defaults.
 */
public final class HudTransform {

    /** Minimum / maximum scale the editor allows. */
    public static final double MIN_SCALE = 0.25;
    public static final double MAX_SCALE = 4.0;

    /**
     * Minimum / maximum opacity the editor allows. Zero is allowed: an element faded all the way out
     * is still listed and still draggable in the editor (which draws its own boxes rather than the
     * element), so there is no way to lose one this way – and on the body dial alone it is how you
     * keep an element's readout while removing its panel entirely.
     */
    public static final double MIN_OPACITY = 0.0;
    public static final double MAX_OPACITY = 1.0;

    public double x = 0.0;
    public double y = 0.0;
    public double scale = 1.0;

    /**
     * Alpha multiplier for the element's panel body (1 = untouched). Keeps the bare {@code opacity}
     * name it has always had on disk, so a layout file stays readable across the split.
     */
    public double opacity = 1.0;

    /**
     * Alpha multiplier for the element's frame – its outline and glow.
     *
     * <p>Boxed, and deliberately without an initializer: {@code null} is how a layout written before
     * the split is recognised, and Gson runs field initializers, so any default here would be
     * indistinguishable from a value the player set. {@link #materialize()} resolves it.
     */
    public Double outlineOpacity;

    /** Alpha multiplier for the element's text and icons. Boxed for the same reason as {@link #outlineOpacity}. */
    public Double textOpacity;

    /**
     * Which parts the single pre-split {@link #opacity} applied to. Read on load and then dropped –
     * never written by current code, so a layout self-cleans the first time it is saved.
     *
     * @deprecated superseded by the three per-part opacities; kept only to migrate old layout files.
     */
    @Deprecated
    public HudOpacityScope opacityScope;

    /** When true the element is completely hidden from the HUD during normal gameplay. */
    public boolean hidden = false;

    public HudTransform() {
    }

    public HudTransform(double x, double y, double scale) {
        this.x = x;
        this.y = y;
        this.scale = scale;
    }

    /**
     * Fills in whichever of the two split opacities is still unset, from the legacy
     * {@link #opacityScope} rule, and drops the scope.
     *
     * <p>The mapping is exactly what each old scope did:
     * {@code BACKGROUND} faded the body alone, {@code BACKGROUND_OUTLINE} the body and the frame,
     * {@code ALL} everything – and a file with no scope at all predates the scope, when opacity
     * applied to the whole element, which is {@code ALL}.
     *
     * <p>Idempotent, and safe on a transform that was never persisted (a fresh one is all-defaults,
     * so every dial resolves to 1).
     */
    public void materialize() {
        HudOpacityScope legacy = opacityScope != null ? opacityScope : HudOpacityScope.ALL;
        if (outlineOpacity == null) {
            outlineOpacity = legacy == HudOpacityScope.BACKGROUND ? MAX_OPACITY : opacity;
        }
        if (textOpacity == null) {
            textOpacity = legacy == HudOpacityScope.ALL ? opacity : MAX_OPACITY;
        }
        opacityScope = null;
    }

    /** The body alpha. */
    public double backgroundOpacity() {
        return clamp(opacity);
    }

    /** The frame (outline + glow) alpha, resolving an un-materialized value the way {@link #materialize} would. */
    public double outlineOpacity() {
        if (outlineOpacity != null) {
            return clamp(outlineOpacity);
        }
        return opacityScope == HudOpacityScope.BACKGROUND ? MAX_OPACITY : clamp(opacity);
    }

    /** The text + icon alpha, resolving an un-materialized value the way {@link #materialize} would. */
    public double textOpacity() {
        if (textOpacity != null) {
            return clamp(textOpacity);
        }
        return opacityScope == null || opacityScope == HudOpacityScope.ALL ? clamp(opacity) : MAX_OPACITY;
    }

    /** True when this transform leaves the element exactly at its default position, size and visibility. */
    public boolean isDefault() {
        return x == 0.0 && y == 0.0 && scale == 1.0 && !hidden
                && backgroundOpacity() == MAX_OPACITY
                && outlineOpacity() == MAX_OPACITY
                && textOpacity() == MAX_OPACITY;
    }

    /** Adds a drag delta (already in GUI-scaled pixels). */
    public void translate(double dx, double dy) {
        this.x += dx;
        this.y += dy;
    }

    /** Adjusts the scale by {@code delta}, clamped to the editor's allowed range. */
    public void addScale(double delta) {
        this.scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, this.scale + delta));
    }

    /** Sets the body opacity, clamped to the editor's allowed range. */
    public void setBackgroundOpacity(double value) {
        materialize();
        this.opacity = clamp(value);
    }

    /** Sets the frame opacity, clamped to the editor's allowed range. */
    public void setOutlineOpacity(double value) {
        materialize();
        this.outlineOpacity = clamp(value);
    }

    /** Sets the text + icon opacity, clamped to the editor's allowed range. */
    public void setTextOpacity(double value) {
        materialize();
        this.textOpacity = clamp(value);
    }

    private static double clamp(double value) {
        return Math.max(MIN_OPACITY, Math.min(MAX_OPACITY, value));
    }
}
