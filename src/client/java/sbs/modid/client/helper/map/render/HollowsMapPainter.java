/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.map.render;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import org.joml.Matrix3x2fStack;
import sbs.modid.client.core.location.hollows.HollowsGeometry;
import sbs.modid.client.core.location.hollows.HollowsRegion;
import sbs.modid.client.core.render.WorldRender;
import sbs.modid.client.helper.map.model.HollowsTrail;
import sbs.modid.client.helper.map.model.MapViewport;
import sbs.modid.client.ui.render.SciFiRender;

import java.util.List;

/**
 * Draws the schematic Crystal Hollows map: coloured regions, the explored trail, icons and the player
 * arrow. Shared by the full-screen map and the HUD minimap so the two cannot drift apart.
 *
 * <p><b>Schematic only.</b> Every shape here is a rectangle from {@link HollowsGeometry} or a cell the
 * player stood in. No block, chunk, entity or other player is ever read or drawn.
 *
 * <p>Areas are filled inside a pose that is translated to the canvas centre and rotated by the
 * viewport's rotation, so they turn with a rotating minimap. Icons and text are drawn upright at
 * positions from {@link MapViewport#toScreen}, so labels never turn. The caller enables the scissor
 * before calling: scissor rectangles are transformed by the pose, and one set inside the rotation
 * would rotate too.
 */
public final class HollowsMapPainter {

    /** Size of a structure icon in pixels. */
    public static final int ICON = 9;

    /** Size of a marker square in pixels. */
    public static final int MARKER = 7;

    public static final int MARKER_RGB = 0xF2D16B;

    private static final int TRAIL_RGB = 0xFFFFFF;

    /** Cached runs per layer, rebuilt when the trail or its generation changes. */
    private static HollowsTrail cachedTrail;
    private static final int[] CACHED_GENERATION = {-1, -1};
    @SuppressWarnings("unchecked")
    private static final List<int[]>[] CACHED_RUNS = new List[2];

    private HollowsMapPainter() {
    }

    /**
     * The coloured regions of one layer: four quadrants plus the Nucleus on the upper layer, Magma
     * Fields alone on the magma layer.
     */
    public static void regions(GuiGraphicsExtractor g, MapViewport vp, HollowsTrail.Layer layer, int fillAlpha) {
        Matrix3x2fStack pose = beginWorld(g, vp);
        if (layer == HollowsTrail.Layer.MAGMA) {
            region(g, vp, HollowsRegion.MAGMA_FIELDS, fillAlpha);
        } else {
            region(g, vp, HollowsRegion.JUNGLE, fillAlpha);
            region(g, vp, HollowsRegion.MITHRIL_DEPOSITS, fillAlpha);
            region(g, vp, HollowsRegion.GOBLIN_HOLDOUT, fillAlpha);
            region(g, vp, HollowsRegion.PRECURSOR_REMNANTS, fillAlpha);
            region(g, vp, HollowsRegion.CRYSTAL_NUCLEUS, Math.min(255, fillAlpha + 0x20));
        }
        pose.popMatrix();
    }

    /** Region names, upright, at each region's centre; skipped when the region is too small to hold one. */
    public static void regionLabels(GuiGraphicsExtractor g, Font font, MapViewport vp, HollowsTrail.Layer layer) {
        HollowsRegion[] regions = layer == HollowsTrail.Layer.MAGMA
                ? new HollowsRegion[]{HollowsRegion.MAGMA_FIELDS}
                : new HollowsRegion[]{HollowsRegion.JUNGLE, HollowsRegion.MITHRIL_DEPOSITS,
                        HollowsRegion.GOBLIN_HOLDOUT, HollowsRegion.PRECURSOR_REMNANTS,
                        HollowsRegion.CRYSTAL_NUCLEUS};
        for (HollowsRegion region : regions) {
            int[] r = HollowsGeometry.rect(region);
            double widthPx = (r[2] - r[0]) * vp.scale();
            String name = region.displayName();
            if (font.width(name) > widthPx - 4) {
                continue;
            }
            // The quadrants' label sits in their outer half, so it is not under the Nucleus.
            double cx = (r[0] + r[2]) / 2.0;
            double cz = (r[1] + r[3]) / 2.0;
            if (region != HollowsRegion.CRYSTAL_NUCLEUS && region != HollowsRegion.MAGMA_FIELDS) {
                cx = (cx * 2 + (cx < HollowsGeometry.CENTER ? r[0] : r[2])) / 3.0;
                cz = (cz * 2 + (cz < HollowsGeometry.CENTER ? r[1] : r[3])) / 3.0;
            }
            double[] p = vp.toScreen(cx, cz);
            g.centeredText(font, Component.literal(name), (int) Math.round(p[0]),
                    (int) Math.round(p[1]) - font.lineHeight / 2, 0xD0000000 | lighten(region.rgb()));
        }
    }

    /** The cells of one layer the player has stood in, as horizontal runs. */
    public static void trail(GuiGraphicsExtractor g, MapViewport vp, HollowsTrail trail, HollowsTrail.Layer layer,
                             int alpha) {
        List<int[]> runs = runs(trail, layer);
        if (runs.isEmpty()) {
            return;
        }
        // Cull against a circle around the centre that covers the whole canvas at any rotation.
        double radiusBlocks = Math.hypot(vp.canvasW(), vp.canvasH()) / 2 / vp.scale() + HollowsTrail.CELL;
        int color = (alpha << 24) | TRAIL_RGB;
        Matrix3x2fStack pose = beginWorld(g, vp);
        int cell = HollowsTrail.CELL;
        for (int[] run : runs) {
            double z0 = (double) run[0] * cell;
            double x0 = (double) run[1] * cell;
            double x1 = (double) (run[2] + 1) * cell;
            if (z0 + cell < vp.centerZ() - radiusBlocks || z0 > vp.centerZ() + radiusBlocks
                    || x1 < vp.centerX() - radiusBlocks || x0 > vp.centerX() + radiusBlocks) {
                continue;
            }
            fillWorld(g, vp, x0, z0, x1, z0 + cell, color);
        }
        pose.popMatrix();
    }

    /**
     * A structure icon centred on {@code (x, y)}. Confirmed: a filled disc with its letter.
     * Unconfirmed: a hollow ring with the letter in grey - told apart by shape, not only by colour.
     */
    public static void structureIcon(GuiGraphicsExtractor g, Font font, int x, int y, String glyph, int rgb,
                                     boolean confirmed, int size) {
        int half = size / 2;
        if (confirmed) {
            SciFiRender.roundedRect(g, x - half - 1, y - half - 1, size + 2, size + 2, (size + 2) / 2, 0x60000000 | rgb);
            SciFiRender.roundedRect(g, x - half, y - half, size, size, half, 0xFF000000 | rgb);
            g.centeredText(font, Component.literal(glyph), x + 1, y - font.lineHeight / 2 + 1, 0xFF101418);
        } else {
            SciFiRender.roundedRect(g, x - half, y - half, size, size, half, 0xA0101418);
            SciFiRender.ring(g, x - half, y - half, size, size, half, 0x90000000 | rgb);
            g.centeredText(font, Component.literal(glyph), x + 1, y - font.lineHeight / 2 + 1, 0xFF9AA0A6);
        }
    }

    /** A hand-placed marker: a square, so it never reads as a structure disc. */
    public static void markerIcon(GuiGraphicsExtractor g, int x, int y, int size) {
        int half = size / 2;
        g.fill(x - half - 1, y - half - 1, x - half + size + 1, y - half + size + 1, 0xC0000000);
        g.fill(x - half, y - half, x - half + size, y - half + size, 0xFF000000 | MARKER_RGB);
    }

    /**
     * The player arrow: a chevron at {@code (x, y)} pointing along the screen direction
     * {@code (dirX, dirY)}, with a dark outline so it reads on every region colour.
     */
    public static void playerArrow(GuiGraphicsExtractor g, double x, double y, double dirX, double dirY,
                                   double size, int rgb) {
        double px = -dirY;
        double py = dirX;
        int tipX = (int) Math.round(x + dirX * size);
        int tipY = (int) Math.round(y + dirY * size);
        int leftX = (int) Math.round(x - dirX * size * 0.7 + px * size * 0.7);
        int leftY = (int) Math.round(y - dirY * size * 0.7 + py * size * 0.7);
        int rightX = (int) Math.round(x - dirX * size * 0.7 - px * size * 0.7);
        int rightY = (int) Math.round(y - dirY * size * 0.7 - py * size * 0.7);
        int notchX = (int) Math.round(x - dirX * size * 0.25);
        int notchY = (int) Math.round(y - dirY * size * 0.25);
        int[][] edges = {{tipX, tipY, leftX, leftY}, {tipX, tipY, rightX, rightY},
                {leftX, leftY, notchX, notchY}, {rightX, rightY, notchX, notchY}};
        for (int[] e : edges) {
            WorldRender.line(g, e[0], e[1], e[2], e[3], 0xC0000000, 4);
        }
        for (int[] e : edges) {
            WorldRender.line(g, e[0], e[1], e[2], e[3], 0xFF000000 | rgb, 2);
        }
    }

    /** Dims a colour toward the background, for unconfirmed labels. */
    public static int dim(int rgb) {
        int r = ((rgb >> 16) & 0xFF) / 2 + 0x20;
        int gr = ((rgb >> 8) & 0xFF) / 2 + 0x20;
        int b = (rgb & 0xFF) / 2 + 0x20;
        return (r << 16) | (gr << 8) | b;
    }

    // ------------------------------------------------------------------ helpers

    private static void region(GuiGraphicsExtractor g, MapViewport vp, HollowsRegion region, int fillAlpha) {
        int[] r = HollowsGeometry.rect(region);
        int rgb = region.rgb();
        fillWorld(g, vp, r[0], r[1], r[2], r[3], (fillAlpha << 24) | rgb);
        int border = 0xB0000000 | rgb;
        // A one-pixel outline, drawn as four thin fills in the rotated frame.
        double px = 1 / vp.scale();
        fillWorld(g, vp, r[0], r[1], r[2], r[1] + px, border);
        fillWorld(g, vp, r[0], r[3] - px, r[2], r[3], border);
        fillWorld(g, vp, r[0], r[1], r[0] + px, r[3], border);
        fillWorld(g, vp, r[2] - px, r[1], r[2], r[3], border);
    }

    /** Pushes a pose whose origin is the canvas centre, rotated like the viewport. */
    private static Matrix3x2fStack beginWorld(GuiGraphicsExtractor g, MapViewport vp) {
        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        pose.translate((float) vp.canvasCenterX(), (float) vp.canvasCenterY());
        pose.rotate((float) vp.rotation());
        return pose;
    }

    /** Fills a world X/Z rectangle inside a pose from {@link #beginWorld}. */
    private static void fillWorld(GuiGraphicsExtractor g, MapViewport vp, double x0, double z0, double x1,
                                  double z1, int color) {
        double s = vp.scale();
        int sx0 = (int) Math.floor((x0 - vp.centerX()) * s);
        int sy0 = (int) Math.floor((z0 - vp.centerZ()) * s);
        int sx1 = (int) Math.ceil((x1 - vp.centerX()) * s);
        int sy1 = (int) Math.ceil((z1 - vp.centerZ()) * s);
        if (sx1 <= sx0) {
            sx1 = sx0 + 1;
        }
        if (sy1 <= sy0) {
            sy1 = sy0 + 1;
        }
        g.fill(sx0, sy0, sx1, sy1, color);
    }

    private static List<int[]> runs(HollowsTrail trail, HollowsTrail.Layer layer) {
        int i = layer.ordinal();
        if (trail != cachedTrail) {
            cachedTrail = trail;
            CACHED_GENERATION[0] = -1;
            CACHED_GENERATION[1] = -1;
        }
        if (CACHED_GENERATION[i] != trail.generation() || CACHED_RUNS[i] == null) {
            CACHED_RUNS[i] = trail.runs(layer);
            CACHED_GENERATION[i] = trail.generation();
        }
        return CACHED_RUNS[i];
    }

    private static int lighten(int rgb) {
        int r = Math.min(255, ((rgb >> 16) & 0xFF) + 0x50);
        int gr = Math.min(255, ((rgb >> 8) & 0xFF) + 0x50);
        int b = Math.min(255, (rgb & 0xFF) + 0x50);
        return (r << 16) | (gr << 8) | b;
    }
}
