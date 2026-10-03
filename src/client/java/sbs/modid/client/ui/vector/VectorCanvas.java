/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.vector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * An off-screen ARGB canvas that fills and strokes {@link SvgPath}s with anti-aliased edges.
 *
 * <p>Drawing code works in <b>viewBox units</b> – the same numbers that sit in the SVG path data –
 * and the canvas maps them onto its pixel buffer through a 2x3 affine transform with
 * {@link #translate}/{@link #scale}/{@link #shearX} and a {@link #push}/{@link #pop} stack, exactly
 * like the {@code <g transform="...">} nesting of an SVG document.
 *
 * <p><b>Why a buffer and not {@code fill()} calls.</b> The GUI render layer can only draw
 * axis-aligned rectangles, so a diagonal edge would have to be emitted as one rectangle per pixel
 * row – thousands of quads every frame for a logo this size, with hard stair-stepped edges. Baking
 * into a buffer instead costs one rasterization per size change and one textured quad per frame,
 * and it is what makes proper anti-aliasing possible. {@link VectorImage} owns that bake.
 *
 * <p><b>Fill rule.</b> Non-zero winding, like SVG's default: a counter (the slots in the logo's
 * {@code B}) is a contour wound against its outer shape. {@link #stroke} generates its own geometry
 * – one quad per segment plus a disc at every join – and normalizes all of it to one winding
 * direction, so overlapping pieces union instead of punching holes in each other.
 */
public final class VectorCanvas implements SvgPath.Transform {

    /** Sub-scanlines per pixel row. Four steps of vertical coverage is plenty at these sizes. */
    private static final int SUBSAMPLES = 4;
    /** Corner segments of a stroke join disc. */
    private static final int JOIN_SEGMENTS = 12;

    private final int[] pixels;
    private final int width;
    private final int height;

    // Affine transform: px = a*x + c*y + e, py = b*x + d*y + f.
    private float a = 1;
    private float b = 0;
    private float c = 0;
    private float d = 1;
    private float e = 0;
    private float f = 0;
    private final List<float[]> stack = new ArrayList<>();

    // Scratch, reused across shapes so a bake does not churn the heap.
    private final List<SvgPath.Contour> contours = new ArrayList<>();
    private float[] coverage = new float[0];
    private float[] crossings = new float[64];
    private int[] directions = new int[64];

    public VectorCanvas(int width, int height) {
        this.width = width;
        this.height = height;
        this.pixels = new int[width * height];
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** The finished image, row-major packed ARGB. */
    public int[] pixels() {
        return pixels;
    }

    // ------------------------------------------------------------------
    // Transform
    // ------------------------------------------------------------------

    public void push() {
        stack.add(new float[] {a, b, c, d, e, f});
    }

    public void pop() {
        float[] m = stack.remove(stack.size() - 1);
        a = m[0];
        b = m[1];
        c = m[2];
        d = m[3];
        e = m[4];
        f = m[5];
    }

    public void translate(float tx, float ty) {
        e += a * tx + c * ty;
        f += b * tx + d * ty;
    }

    public void scale(float sx, float sy) {
        a *= sx;
        b *= sx;
        c *= sy;
        d *= sy;
    }

    /** Horizontal shear: {@code x' = x + k*y}. This is what gives the logo its italic slant. */
    public void shearX(float k) {
        c += a * k;
        d += b * k;
    }

    @Override
    public float px(float x, float y) {
        return a * x + c * y + e;
    }

    @Override
    public float py(float x, float y) {
        return b * x + d * y + f;
    }

    /** Average scale factor of the current transform – converts a stroke width into device pixels. */
    public float scaleFactor() {
        return (float) Math.sqrt(Math.abs(a * d - b * c));
    }

    /** A horizontal gradient given in viewBox units, mapped through the current transform. */
    public VectorPaint gradientX(float x0, int c0, float x1, int c1) {
        return VectorPaint.linearX(px(x0, 0), c0, px(x1, 0), c1);
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    /** Fills a path with the non-zero winding rule. */
    public void fill(SvgPath path, VectorPaint paint) {
        contours.clear();
        path.flatten(this, contours);
        rasterize(contours, paint);
    }

    /** Fills an axis-aligned rectangle given in viewBox units. */
    public void fillRect(float x, float y, float w, float h, VectorPaint paint) {
        contours.clear();
        contours.add(new SvgPath.Contour(new float[] {
                px(x, y), py(x, y),
                px(x + w, y), py(x + w, y),
                px(x + w, y + h), py(x + w, y + h),
                px(x, y + h), py(x, y + h)}, true));
        rasterize(contours, paint);
    }

    /**
     * Strokes a path with a constant width – round joins, butt caps.
     *
     * <p>The wordmark font is monoline, so its glyphs are stored as centre lines and given their
     * weight here instead of being drawn as filled outlines. That keeps the glyph data readable and
     * lets the weight be tuned in one place.
     */
    public void stroke(SvgPath path, float strokeWidth, VectorPaint paint) {
        contours.clear();
        path.flatten(this, contours);
        float half = Math.max(0.35F, strokeWidth * scaleFactor() * 0.5F);

        List<SvgPath.Contour> pieces = new ArrayList<>();
        for (SvgPath.Contour contour : contours) {
            float[] p = contour.pts();
            int n = contour.count();
            int segments = contour.closed() ? n : n - 1;
            for (int i = 0; i < segments; i++) {
                int j = (i + 1) % n;
                addSegment(pieces, p[i * 2], p[i * 2 + 1], p[j * 2], p[j * 2 + 1], half);
            }
            // A join disc at every vertex two segments meet at; on an open contour that is
            // everything but the two ends, on a closed one it is every vertex.
            int firstJoin = contour.closed() ? 0 : 1;
            int lastJoin = contour.closed() ? n - 1 : n - 2;
            for (int i = firstJoin; i <= lastJoin; i++) {
                addDisc(pieces, p[i * 2], p[i * 2 + 1], half);
            }
        }
        rasterize(pieces, paint);
    }

    private static void addSegment(List<SvgPath.Contour> out, float x0, float y0, float x1, float y1,
                                   float half) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1.0E-4F) {
            return; // Flattening can produce coincident points; the join disc covers them.
        }
        float nx = -dy / len * half;
        float ny = dx / len * half;
        out.add(wind(new float[] {
                x0 + nx, y0 + ny,
                x1 + nx, y1 + ny,
                x1 - nx, y1 - ny,
                x0 - nx, y0 - ny}));
    }

    private static void addDisc(List<SvgPath.Contour> out, float cx, float cy, float r) {
        float[] pts = new float[JOIN_SEGMENTS * 2];
        for (int i = 0; i < JOIN_SEGMENTS; i++) {
            double angle = 2 * Math.PI * i / JOIN_SEGMENTS;
            pts[i * 2] = cx + (float) Math.cos(angle) * r;
            pts[i * 2 + 1] = cy + (float) Math.sin(angle) * r;
        }
        out.add(wind(pts));
    }

    /**
     * Forces a contour to positive signed area. Every stroke piece must wind the same way, or a
     * quad and the disc overlapping it would cancel to winding zero and leave a hole at the join.
     */
    private static SvgPath.Contour wind(float[] pts) {
        int n = pts.length / 2;
        float twiceArea = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            twiceArea += pts[i * 2] * pts[j * 2 + 1] - pts[j * 2] * pts[i * 2 + 1];
        }
        if (twiceArea < 0) {
            for (int i = 0, k = n - 1; i < k; i++, k--) {
                float x = pts[i * 2];
                float y = pts[i * 2 + 1];
                pts[i * 2] = pts[k * 2];
                pts[i * 2 + 1] = pts[k * 2 + 1];
                pts[k * 2] = x;
                pts[k * 2 + 1] = y;
            }
        }
        return new SvgPath.Contour(pts, true);
    }

    // ------------------------------------------------------------------
    // Scanline rasterizer
    // ------------------------------------------------------------------

    /**
     * Fills the contours into the buffer.
     *
     * <p>Per pixel row, {@value #SUBSAMPLES} sub-scanlines are intersected with every edge; the
     * spans where the winding number is non-zero are accumulated into a per-pixel coverage value,
     * with the partially covered pixels at each end of a span weighted by how much of them the span
     * actually covers. That gives clean edges in both axes without any supersampled buffer.
     */
    private void rasterize(List<SvgPath.Contour> shapes, VectorPaint paint) {
        int edges = 0;
        for (SvgPath.Contour contour : shapes) {
            edges += contour.count();
        }
        if (edges == 0) {
            return;
        }
        float[] ex0 = new float[edges];
        float[] ey0 = new float[edges];
        float[] ex1 = new float[edges];
        float[] ey1 = new float[edges];
        int count = 0;
        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        float minX = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        for (SvgPath.Contour contour : shapes) {
            float[] p = contour.pts();
            int n = contour.count();
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                float x0 = p[i * 2];
                float y0 = p[i * 2 + 1];
                float x1 = p[j * 2];
                float y1 = p[j * 2 + 1];
                if (y0 == y1) {
                    continue; // Horizontal edges never cross a scanline.
                }
                ex0[count] = x0;
                ey0[count] = y0;
                ex1[count] = x1;
                ey1[count] = y1;
                count++;
                minY = Math.min(minY, Math.min(y0, y1));
                maxY = Math.max(maxY, Math.max(y0, y1));
                minX = Math.min(minX, Math.min(x0, x1));
                maxX = Math.max(maxX, Math.max(x0, x1));
            }
        }
        if (count == 0) {
            return;
        }

        int rowStart = Math.max(0, (int) Math.floor(minY));
        int rowEnd = Math.min(height - 1, (int) Math.ceil(maxY));
        int colStart = Math.max(0, (int) Math.floor(minX));
        int colEnd = Math.min(width - 1, (int) Math.ceil(maxX));
        if (rowStart > rowEnd || colStart > colEnd) {
            return;
        }
        int span = colEnd - colStart + 1;
        if (coverage.length < span) {
            coverage = new float[span];
        }
        if (crossings.length < count) {
            crossings = new float[count];
            directions = new int[count];
        }

        for (int y = rowStart; y <= rowEnd; y++) {
            Arrays.fill(coverage, 0, span, 0F);
            boolean touched = false;
            for (int s = 0; s < SUBSAMPLES; s++) {
                float sy = y + (s + 0.5F) / SUBSAMPLES;
                int hits = 0;
                for (int i = 0; i < count; i++) {
                    float y0 = ey0[i];
                    float y1 = ey1[i];
                    if (sy < Math.min(y0, y1) || sy >= Math.max(y0, y1)) {
                        continue;
                    }
                    float t = (sy - y0) / (y1 - y0);
                    // Insertion sort on the fly – a scanline rarely crosses more than a few edges.
                    float x = ex0[i] + t * (ex1[i] - ex0[i]);
                    int dir = y1 > y0 ? 1 : -1;
                    int k = hits++;
                    while (k > 0 && crossings[k - 1] > x) {
                        crossings[k] = crossings[k - 1];
                        directions[k] = directions[k - 1];
                        k--;
                    }
                    crossings[k] = x;
                    directions[k] = dir;
                }
                int winding = 0;
                float spanStart = 0;
                for (int i = 0; i < hits; i++) {
                    int before = winding;
                    winding += directions[i];
                    if (before == 0 && winding != 0) {
                        spanStart = crossings[i];
                    } else if (before != 0 && winding == 0) {
                        touched |= addSpan(spanStart, crossings[i], colStart, colEnd);
                    }
                }
            }
            if (touched) {
                compose(y, colStart, span, paint);
            }
        }
    }

    /** Adds a horizontal span's contribution (one sub-scanline's worth) to the coverage row. */
    private boolean addSpan(float from, float to, int colStart, int colEnd) {
        float x0 = Math.max(from, colStart);
        float x1 = Math.min(to, colEnd + 1F);
        if (x1 <= x0) {
            return false;
        }
        float weight = 1F / SUBSAMPLES;
        int first = (int) Math.floor(x0);
        int last = (int) Math.floor(x1);
        if (last >= x1) { // x1 landed exactly on a pixel boundary.
            last--;
        }
        if (first == last) {
            coverage[first - colStart] += (x1 - x0) * weight;
            return true;
        }
        coverage[first - colStart] += (first + 1 - x0) * weight;
        for (int x = first + 1; x < last; x++) {
            coverage[x - colStart] += weight;
        }
        if (last <= colEnd) {
            coverage[last - colStart] += (x1 - last) * weight;
        }
        return true;
    }

    /** Blends one finished coverage row into the pixel buffer, source-over. */
    private void compose(int y, int colStart, int span, VectorPaint paint) {
        int rowOffset = y * width;
        for (int i = 0; i < span; i++) {
            float cov = coverage[i];
            if (cov <= 0.002F) {
                continue;
            }
            int x = colStart + i;
            int src = paint.colorAt(x, y);
            int srcAlpha = (src >>> 24) & 0xFF;
            if (srcAlpha == 0) {
                continue;
            }
            float sa = srcAlpha / 255F * Math.min(1F, cov);
            int index = rowOffset + x;
            int dst = pixels[index];
            float da = ((dst >>> 24) & 0xFF) / 255F;
            float outA = sa + da * (1 - sa);
            if (outA <= 0.0001F) {
                continue;
            }
            pixels[index] = (Math.round(outA * 255) << 24)
                    | (channel(src, dst, 16, sa, da, outA) << 16)
                    | (channel(src, dst, 8, sa, da, outA) << 8)
                    | channel(src, dst, 0, sa, da, outA);
        }
    }

    private static int channel(int src, int dst, int shift, float sa, float da, float outA) {
        float s = ((src >> shift) & 0xFF) / 255F;
        float t = ((dst >> shift) & 0xFF) / 255F;
        return Math.clamp(Math.round((s * sa + t * da * (1 - sa)) / outA * 255), 0, 255);
    }
}
