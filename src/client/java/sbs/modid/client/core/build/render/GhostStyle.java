/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.build.render;

import sbs.modid.client.core.render.OverlayColor;

/**
 * How a hologram is drawn - one snapshot per frame, taken from the settings of whichever module owns
 * the hologram (Garden Blueprint's page for a plot copy, Build Tools' for everything else), so both
 * renderers of one frame agree on it.
 *
 * @param radius        only cells within this many blocks of the player are drawn
 * @param edgeAlpha     outline alpha, 0-255
 * @param lineWidth     outline thickness in pixels
 * @param fill          flat fill where no block model is drawn
 * @param fillAlpha     fill alpha, 0-255
 * @param models        real translucent block models for cells still to place
 * @param modelOpacity  model opacity percent, already floored at {@link GhostModels#MIN_OPACITY}
 * @param modelsOnWrong a model over a wrong block too
 * @param showCorrect   outline cells that are already right
 * @param missingRgb    colour of a cell still to place
 * @param wrongRgb      colour of a wrong block (or, before a paste, a collision)
 * @param correctRgb    colour of a correct block
 * @param removeRgb     colour of a block an edit would remove
 */
public record GhostStyle(int radius, int edgeAlpha, int lineWidth, boolean fill, int fillAlpha,
                         boolean models, int modelOpacity, boolean modelsOnWrong, boolean showCorrect,
                         int missingRgb, int wrongRgb, int correctRgb, int removeRgb) {

    /** An {@code RRGGBB} hex parsed to plain RGB, or the fallback when it does not parse. */
    public static int rgb(String hex, int fallbackRgb) {
        Integer parsed = OverlayColor.parseHex(hex);
        return (parsed != null ? parsed : fallbackRgb) & 0xFFFFFF;
    }

    /** A percentage setting clamped to a range and turned into 0-255 alpha. */
    public static int alpha(int percent, int min, int max) {
        return Math.max(min, Math.min(max, percent)) * 255 / 100;
    }

    public int colorFor(GhostCollector.Status status) {
        return switch (status) {
            case MISSING -> missingRgb;
            case WRONG -> wrongRgb;
            case CORRECT -> correctRgb;
            case REMOVE -> removeRgb;
        };
    }
}
