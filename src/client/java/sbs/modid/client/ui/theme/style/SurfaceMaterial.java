/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.theme.style;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * What a surface is made of - the hook that lets a style put wood grain, brass rivets or arcane
 * runes on every panel in the mod without any screen knowing about it.
 *
 * <p><b>Why this exists at all.</b> A colour and a corner radius reach the whole mod for free,
 * because every screen reads the mutable {@code SBSTheme} constants. Material does not: there is no
 * constant for "draw a plank seam here". The previous style only ever changed colours and radii, and
 * that is exactly why surfaces kept being "left out" one at a time - each new look needed another
 * screen edited by hand.
 *
 * <p>So material is injected at the <b>one place every surface already goes through</b>:
 * {@link sbs.modid.client.ui.render.SciFiRender}'s four primitives, which between them draw every
 * panel, card, row, button and HUD element in the mod. A material implemented here reaches all of
 * them at once, and a screen added next year is covered the day it is written. Nothing can be left
 * out, because nothing gets a say.
 *
 * <p>Implementations must be <b>cheap and stateless</b>. These methods run several hundred times per
 * frame, so a body pass is a handful of {@code fill} calls, never a per-pixel loop; the rounded-rect
 * primitive itself is the budget to stay near.
 */
public interface SurfaceMaterial {

    /** The material of a style that only reshapes and recolours - Classic, Futuristic, Vanilla. */
    SurfaceMaterial NONE = new SurfaceMaterial() {
    };

    /**
     * How much visual weight a surface carries, worked out from its size alone.
     *
     * <p>Size is the only signal available at the choke point, and it is the right one: material is
     * about how substantial something looks, and that is what size means. It also keeps the hard-won
     * slot rule automatic - an 18 px inventory cell can never collect plank seams and rivets,
     * whatever the style does to the window around it.
     */
    enum Weight {
        /** An item cell or a tiny square control. Never textured - a grid must stay readable. */
        SLOT,
        /** A row, a card, a button. Light treatment only. */
        CARD,
        /** A window, a floating panel, a large HUD block. Full treatment, ornament allowed. */
        PANEL;

        /** Classifies a rectangle. Deliberately generous about what counts as small. */
        public static Weight of(int w, int h) {
            if (w < 26 && h < 26) {
                return SLOT;
            }
            return w >= 140 && h >= 90 ? PANEL : CARD;
        }
    }

    /**
     * Painted over a filled body, after the base colour is down.
     *
     * @param color the fill that was just painted - derive from it so the material follows a custom
     *              theme colour instead of pinning the style to its own palette
     */
    default void paintBody(GuiGraphicsExtractor g, int x, int y, int w, int h,
                           int radius, int color, Weight weight) {
    }

    /**
     * Painted over the border ring of a bordered surface, before the body goes on top of it.
     * The place for bevels, double frames, rivets and rune ticks.
     */
    default void paintFrame(GuiGraphicsExtractor g, int x, int y, int w, int h,
                            int radius, int border, Weight weight) {
    }

    /**
     * Replaces the soft outer glow. Return {@code true} when the material drew its own (or wants
     * none at all); {@code false} lets the default expanding-rings glow run.
     */
    default boolean paintGlow(GuiGraphicsExtractor g, int x, int y, int w, int h,
                              int radius, int color, int layers) {
        return false;
    }

    // ------------------------------------------------------------------
    // Shared helpers - every material needs the same few colour moves.
    // ------------------------------------------------------------------

    /** The same colour at a different alpha. */
    static int alpha(int color, int a) {
        return (Math.max(0, Math.min(255, a)) << 24) | (color & 0xFFFFFF);
    }

    /** Moves a colour towards white ({@code amount > 0}) or black ({@code amount < 0}). */
    static int shade(int color, float amount) {
        int a = color >>> 24;
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        if (amount >= 0F) {
            r = Math.round(r + (255 - r) * amount);
            g = Math.round(g + (255 - g) * amount);
            b = Math.round(b + (255 - b) * amount);
        } else {
            float keep = 1F + amount;
            r = Math.round(r * keep);
            g = Math.round(g * keep);
            b = Math.round(b * keep);
        }
        return (a << 24) | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    /** Blends {@code b} over {@code a} by {@code t} (0..1), keeping {@code a}'s alpha. */
    static int mix(int a, int b, float t) {
        int keep = a >>> 24;
        int ar = (a >> 16) & 0xFF;
        int ag = (a >> 8) & 0xFF;
        int ab = a & 0xFF;
        int br = (b >> 16) & 0xFF;
        int bg = (b >> 8) & 0xFF;
        int bb = b & 0xFF;
        return (keep << 24)
                | (clamp(Math.round(ar + (br - ar) * t)) << 16)
                | (clamp(Math.round(ag + (bg - ag) * t)) << 8)
                | clamp(Math.round(ab + (bb - ab) * t));
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : Math.min(v, 255);
    }

    /**
     * A cheap deterministic hash for grain and speckle placement.
     *
     * <p>Deterministic on the surface's own coordinates rather than random, so a panel's grain does
     * not crawl while the window is dragged and nothing has to be cached per surface.
     */
    static int noise(int x, int y) {
        int n = x * 374761393 + y * 668265263;
        n = (n ^ (n >>> 13)) * 1274126177;
        return (n ^ (n >>> 16)) & 0x7FFFFFFF;
    }
}
