/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.vector;

/**
 * What a {@link VectorCanvas} fills a shape with, evaluated per device pixel.
 *
 * <p>Colors are packed ARGB, the same convention as {@link sbs.modid.client.ui.theme.SBSTheme}. The
 * paint's own alpha is multiplied with the pixel's anti-aliasing coverage, so a translucent paint
 * and a soft edge compose the way they should.
 */
@FunctionalInterface
public interface VectorPaint {

    /** Color at a device pixel. Coordinates are pixels in the baked image, not viewBox units. */
    int colorAt(int x, int y);

    /** One flat color everywhere. */
    static VectorPaint solid(int argb) {
        return (x, y) -> argb;
    }

    /**
     * Horizontal gradient between two device x positions, clamped outside them. Used for the rules
     * flanking "SIMPLIFIED", which fade out towards the edges of the screen.
     */
    static VectorPaint linearX(float x0, int c0, float x1, int c1) {
        float span = x1 - x0;
        if (Math.abs(span) < 1.0E-4F) {
            return solid(c1);
        }
        return (x, y) -> lerp(c0, c1, (x + 0.5F - x0) / span);
    }

    /** Component-wise ARGB interpolation, {@code t} clamped to 0..1. */
    static int lerp(int from, int to, float t) {
        float f = Math.clamp(t, 0F, 1F);
        int a = Math.round(((from >>> 24) & 0xFF) + (((to >>> 24) & 0xFF) - ((from >>> 24) & 0xFF)) * f);
        int r = Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * f);
        int g = Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * f);
        int b = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * f);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** The same color at a different alpha (0..1), keeping its RGB. */
    static int withAlpha(int argb, float alpha) {
        int a = Math.round(((argb >>> 24) & 0xFF) * Math.clamp(alpha, 0F, 1F));
        return (a << 24) | (argb & 0x00FFFFFF);
    }
}
