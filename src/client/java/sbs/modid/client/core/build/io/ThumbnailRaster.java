/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.build.io;

import sbs.modid.client.core.build.model.Schematic;

/**
 * Draws a small isometric picture of a schematic into an ARGB pixel array - the thumbnail a saved
 * build gets in the library and the Quick Paste grid.
 *
 * <p><b>On the CPU, on purpose.</b> An off-screen GPU render would look richer, but the render-target
 * API has moved under this tree more than once, and a thumbnail is made on the library's background
 * thread at save time where there is no render context at all. Each visible block is a little cube in
 * its map colour, top face bright, the two side faces shaded - enough to recognise a build at 128
 * pixels, and it cannot break with the renderer.
 *
 * <p>Pure: colours come in as one RGB per palette entry, so it is tested without the game.
 */
public final class ThumbnailRaster {

    /** Side of the square thumbnail in pixels. */
    public static final int SIZE = 128;

    private static final int MARGIN = 4;

    private ThumbnailRaster() {
    }

    /**
     * Renders {@code schematic} viewed from the south-east, above.
     *
     * @param rgb one colour per palette entry (index 0, air, is never drawn)
     * @return {@code SIZE * SIZE} ARGB pixels, transparent where nothing was drawn
     */
    public static int[] render(Schematic schematic, int[] rgb) {
        int[] pixels = new int[SIZE * SIZE];
        int w = schematic.width();
        int h = schematic.height();
        int l = schematic.length();
        // Iso projection: screen x = (x - z) * t, screen y = (x + z) * t / 2 - y * t.
        double spanX = (w + l) * 1.0;
        double spanY = (w + l) / 2.0 + h;
        double t = (SIZE - 2 * MARGIN) / Math.max(spanX, spanY);
        double originX = SIZE / 2.0 - (w - l) * t / 2.0;
        double originY = MARGIN + h * t + (SIZE - 2 * MARGIN - spanY * t) / 2.0;

        // Back to front: lower x+z first, and bottom to top within it, so nearer and higher cubes
        // paint over farther and lower ones.
        for (int sum = 0; sum <= (w - 1) + (l - 1); sum++) {
            for (int y = 0; y < h; y++) {
                for (int x = Math.max(0, sum - (l - 1)); x <= Math.min(w - 1, sum); x++) {
                    int z = sum - x;
                    int palette = schematic.paletteAt(x, y, z);
                    if (palette == 0 || hidden(schematic, x, y, z)) {
                        continue;
                    }
                    int color = rgb[palette] & 0xFFFFFF;
                    double sx = originX + (x - z) * t;
                    double sy = originY + (x + z) * t / 2.0 - y * t;
                    cube(pixels, sx, sy, t, color);
                }
            }
        }
        return pixels;
    }

    /** A cube whose three visible neighbours are all solid cannot be seen from this angle. */
    private static boolean hidden(Schematic s, int x, int y, int z) {
        return s.paletteAt(x + 1, y, z) != 0 && s.paletteAt(x, y + 1, z) != 0 && s.paletteAt(x, y, z + 1) != 0;
    }

    /** One cube: its top diamond and its two front faces, with (sx, sy) the top face's back corner. */
    private static void cube(int[] pixels, double sx, double sy, double t, int rgb) {
        double half = t / 2.0;
        // Top face: back (sx, sy), right (sx + t, sy + half), front (sx, sy + t), left (sx - t, sy + half).
        quad(pixels, sx, sy, sx + t, sy + half, sx, sy + t, sx - t, sy + half, shade(rgb, 1.0));
        // Left (south) face and right (east) face below the top.
        quad(pixels, sx - t, sy + half, sx, sy + t, sx, sy + 2 * t, sx - t, sy + half + t, shade(rgb, 0.78));
        quad(pixels, sx, sy + t, sx + t, sy + half, sx + t, sy + half + t, sx, sy + 2 * t, shade(rgb, 0.62));
    }

    private static int shade(int rgb, double factor) {
        int r = (int) Math.min(255, ((rgb >> 16) & 0xFF) * factor);
        int g = (int) Math.min(255, ((rgb >> 8) & 0xFF) * factor);
        int b = (int) Math.min(255, (rgb & 0xFF) * factor);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** Fills a convex quad given clockwise, by pixel-centre inside tests over its bounding box. */
    private static void quad(int[] pixels, double x0, double y0, double x1, double y1,
                             double x2, double y2, double x3, double y3, int argb) {
        int minX = (int) Math.floor(Math.min(Math.min(x0, x1), Math.min(x2, x3)));
        int maxX = (int) Math.ceil(Math.max(Math.max(x0, x1), Math.max(x2, x3)));
        int minY = (int) Math.floor(Math.min(Math.min(y0, y1), Math.min(y2, y3)));
        int maxY = (int) Math.ceil(Math.max(Math.max(y0, y1), Math.max(y2, y3)));
        if (maxX - minX <= 1 && maxY - minY <= 1) {
            // Sub-pixel cube face: one dot, or a big build vanishes into nothing.
            set(pixels, (int) x0, (int) y0, argb);
            return;
        }
        for (int py = Math.max(0, minY); py < Math.min(SIZE, maxY); py++) {
            for (int px = Math.max(0, minX); px < Math.min(SIZE, maxX); px++) {
                double cx = px + 0.5;
                double cy = py + 0.5;
                if (edge(x0, y0, x1, y1, cx, cy) >= 0 && edge(x1, y1, x2, y2, cx, cy) >= 0
                        && edge(x2, y2, x3, y3, cx, cy) >= 0 && edge(x3, y3, x0, y0, cx, cy) >= 0) {
                    pixels[py * SIZE + px] = argb;
                }
            }
        }
    }

    private static double edge(double ax, double ay, double bx, double by, double px, double py) {
        return (bx - ax) * (py - ay) - (by - ay) * (px - ax);
    }

    private static void set(int[] pixels, int x, int y, int argb) {
        if (x >= 0 && y >= 0 && x < SIZE && y < SIZE) {
            pixels[y * SIZE + x] = argb;
        }
    }
}
