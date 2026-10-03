/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.theme.style;

/**
 * One complete look for the mod: its base colours, what it does to every surface, and what those
 * surfaces are made of.
 *
 * <p>A definition is a plain object rather than another branch in a switch, so adding a style is
 * adding one class and one enum constant - nothing else in the mod changes, and no screen has to
 * learn the new name. The three parts are deliberately separate concerns:
 *
 * <ul>
 *   <li>{@link #palette()} - colour identity, only for styles that have one, and only as a default.
 *   <li>{@link #applySurfaces()} - the transform over the derived colours and the corner radii.
 *       Runs <b>after</b> the theme engine, so it flattens/lifts/fades real themed values and keeps
 *       working under a custom accent.
 *   <li>{@link #material()} - what {@code SciFiRender} paints on top, which is how a look reaches
 *       every surface in the mod at once instead of screen by screen.
 * </ul>
 */
public interface StyleDefinition {

    /** Name shown in the settings row. */
    String displayName();

    /** One line describing the look, for the setting's hover text. */
    String tagline();

    /**
     * The base colours this style is built around, or {@code null} to keep the player's.
     * Consulted only while the Theme colours are still at stock - see {@link StylePalette}.
     */
    default StylePalette palette() {
        return null;
    }

    /**
     * Rewrites the mutable {@code SBSTheme} geometry and surface constants. Called immediately after
     * the theme engine has derived the colour family, never before.
     */
    void applySurfaces();

    /** What every surface is made of. {@link SurfaceMaterial#NONE} for colour-and-shape-only styles. */
    default SurfaceMaterial material() {
        return SurfaceMaterial.NONE;
    }

    /**
     * Sprite name for this style's tooltip, resolving to
     * {@code textures/gui/sprites/tooltip/<name>_background.png} and {@code _frame.png}.
     *
     * <p>The one surface a material cannot reach: tooltips are drawn by vanilla from a nine-slice
     * texture, so there is no fill call to intercept and the look has to be a real asset. They are
     * 8x8 images - a one-pixel ring around a fill - so each style simply ships its own rather than
     * being the single navy rectangle left over in an oak or brass client.
     */
    default String tooltipSprite() {
        return "sbs";
    }
}
