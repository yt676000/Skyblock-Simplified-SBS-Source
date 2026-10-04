/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.build.render;

/**
 * Colour and alpha of ghost geometry, kept free of game types so the floors are unit-testable.
 *
 * <p>Every ghost is drawn through a translucent pipeline that discards fragments under alpha 0.1, and
 * the vertex alpha is multiplied by the texture's own alpha first. Water's still texture is 180/255,
 * so a vertex alpha that is fine for an opaque block can still end up under the cutoff for water. The
 * floors here keep every ghost above it.
 */
public final class GhostTint {

    /** Lowest usable model opacity in percent; below it the pipeline's alpha cutout eats the ghost. */
    public static final int MIN_OPACITY = 20;

    /**
     * Lowest vertex alpha any ghost is drawn with: {@link #MIN_OPACITY} as 0-255. Against water's
     * texture alpha of 180 that lands at about 0.14, still above the 0.1 cutoff.
     */
    public static final int MIN_ALPHA = MIN_OPACITY * 255 / 100;

    /**
     * Vanilla's default water colour, used when the biome tint at the cell cannot be read. Water's
     * still texture is grey; drawn untinted it reads as a grey box rather than as water.
     */
    public static final int WATER_FALLBACK_RGB = 0x3F76E4;

    /**
     * Vanilla's plains grass colour, for a biome-tinted block model whose tint could not be read at
     * the cell and whose plain colour is "none" too - sugar cane answers -1 outside a level.
     */
    public static final int GRASS_FALLBACK_RGB = 0x91BD59;

    private GhostTint() {
    }

    /**
     * The tint of one biome-tinted layer of a block model, opaque so the ghost alpha survives
     * {@code ARGB.multiply}.
     *
     * @param inWorld what the source's {@code colorInWorld} returned at the ghost's cell
     * @param plain   the source's {@code color(state)}: used when {@code inWorld} is unresolved (-1 or
     *                black), unless it is unresolved too, then {@link #GRASS_FALLBACK_RGB}
     */
    public static int blockColor(int inWorld, int plain) {
        int rgb;
        if (resolved(inWorld)) {
            rgb = inWorld;
        } else if (resolved(plain)) {
            rgb = plain;
        } else {
            rgb = GRASS_FALLBACK_RGB;
        }
        return 0xFF000000 | rgb & 0xFFFFFF;
    }

    /** -1 is a tint source's "no colour" answer, black a failed lookup; neither is a biome colour. */
    private static boolean resolved(int argb) {
        return argb != -1 && (argb & 0xFFFFFF) != 0;
    }

    /** A model-opacity setting in percent as 0-255 alpha, clamped to {@link #MIN_OPACITY}..100. */
    public static int modelAlpha(int opacityPercent) {
        return Math.max(MIN_OPACITY, Math.min(100, opacityPercent)) * 255 / 100;
    }

    /**
     * Alpha of the water box inside a waterlogged block: half the model's, so the block itself stays
     * readable, but never under {@link #MIN_ALPHA} - at minimum opacity half would be discarded.
     */
    public static int washAlpha(int modelAlpha) {
        return Math.max(MIN_ALPHA, modelAlpha / 2);
    }

    /**
     * The ARGB vertex colour of a fluid ghost.
     *
     * @param tinted  whether the fluid's model has a tint source (water does, lava does not)
     * @param tintArgb what that source returned at the cell; ignored when {@code tinted} is false
     * @param alpha   requested alpha 0-255, raised to {@link #MIN_ALPHA}
     */
    public static int fluidColor(boolean tinted, int tintArgb, int alpha) {
        int rgb;
        if (!tinted) {
            rgb = 0xFFFFFF;   // lava's texture carries its own colour
        } else if (tintArgb == -1 || (tintArgb & 0xFFFFFF) == 0) {
            // -1 is the tint source's "no colour" answer outside a level, 0 a failed lookup: either
            // way not a water colour.
            rgb = WATER_FALLBACK_RGB;
        } else {
            rgb = tintArgb & 0xFFFFFF;
        }
        int a = Math.max(MIN_ALPHA, Math.min(255, alpha));
        return a << 24 | rgb;
    }
}
