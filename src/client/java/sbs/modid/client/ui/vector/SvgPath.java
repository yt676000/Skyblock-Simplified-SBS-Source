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
 * A parsed SVG path – the {@code d} attribute of {@code <path>}, nothing more.
 *
 * <p>Artwork in SBS is stored as SVG path data (see
 * {@link sbs.modid.client.ui.titlescreen.SbsBrandArt}) rather than as a PNG, so the same drawing
 * stays sharp at every GUI scale and every window size: the paths are re-rasterized whenever the
 * target size changes instead of being stretched. This class is only the geometry half – parsing
 * and flattening; {@link VectorCanvas} turns the flattened outlines into pixels.
 *
 * <p>Supported commands are {@code M L H V C S Q T Z} in both absolute and relative form, which is
 * everything the bundled artwork uses. Elliptical arcs ({@code A}) are <b>not</b> supported – draw
 * them as cubics, the way every vector editor exports them anyway.
 *
 * <p>Parsing happens once per path string ({@link #of}); flattening happens per rasterization and
 * is done in <em>device</em> space: the control points are transformed first and only then split
 * into line segments, so the subdivision density follows the size the art is actually drawn at. A
 * curve on a 40 px wordmark costs a handful of segments; the same curve on a 4K logo gets as many
 * as it needs.
 */
public final class SvgPath {

    /** A transform from path (viewBox) space into device pixels. Implemented by {@link VectorCanvas}. */
    public interface Transform {
        float px(float x, float y);

        float py(float x, float y);
    }

    /**
     * One flattened outline in device space: {@code pts} holds x/y pairs. {@code closed} is what
     * {@code Z} produced – it matters for stroking (a closed contour has a join at its start point
     * instead of two loose ends), never for filling, where every contour is implicitly closed.
     */
    public record Contour(float[] pts, boolean closed) {
        public int count() {
            return pts.length / 2;
        }
    }

    /** One parsed command: the operator letter plus exactly the arguments it takes. */
    private record Cmd(char op, float[] args) {
    }

    /** Device-space length of one flattened curve segment. Below ~2 px the extra points are free detail. */
    private static final float FLATNESS = 2.0F;
    private static final int MIN_STEPS = 3;
    private static final int MAX_STEPS = 96;

    private static final float[] NO_ARGS = new float[0];

    private final List<Cmd> commands;

    private SvgPath(List<Cmd> commands) {
        this.commands = commands;
    }

    /** Parses SVG path data. Malformed input yields the commands parsed so far rather than throwing. */
    public static SvgPath of(String d) {
        List<Cmd> out = new ArrayList<>();
        int i = 0;
        int len = d.length();
        char op = 0;
        while (i < len) {
            char ch = d.charAt(i);
            if (ch == ',' || ch <= ' ') {
                i++;
                continue;
            }
            if (isCommand(ch)) {
                op = ch;
                i++;
            } else if (op == 0) {
                i++; // Leading junk before any command – skip it.
                continue;
            } else if (op == 'M') {
                op = 'L'; // Repeated coordinates after a moveto are implicit linetos, per the spec.
            } else if (op == 'm') {
                op = 'l';
            }

            int need = argCount(op);
            if (need == 0) {
                out.add(new Cmd(op, NO_ARGS));
                continue;
            }
            float[] args = new float[need];
            int at = i;
            for (int k = 0; k < need; k++) {
                at = skipSeparators(d, at);
                int end = numberEnd(d, at);
                if (end == at) {
                    return new SvgPath(out); // Truncated command – keep what is already valid.
                }
                args[k] = Float.parseFloat(d.substring(at, end));
                at = end;
            }
            i = at;
            out.add(new Cmd(op, args));
        }
        return new SvgPath(out);
    }

    /**
     * Flattens this path into device-space contours and appends them to {@code out}.
     *
     * <p>Zero-area contours (a lone {@code M}, or a subpath that collapsed to a point) are dropped:
     * they contribute nothing to a fill and would only add degenerate edges to the rasterizer.
     */
    public void flatten(Transform transform, List<Contour> out) {
        Builder b = new Builder(transform, out);
        // Current point and subpath start are kept in PATH space, because relative commands are
        // relative to them; only the emitted points go through the transform.
        float cx = 0;
        float cy = 0;
        float sx = 0;
        float sy = 0;
        // Reflection point for the smooth variants S/T; reset to the current point after any other command.
        float rcx = 0;
        float rcy = 0;
        char prev = 0;

        for (Cmd cmd : commands) {
            char op = cmd.op();
            float[] a = cmd.args();
            boolean rel = Character.isLowerCase(op);
            float ox = rel ? cx : 0;
            float oy = rel ? cy : 0;

            switch (Character.toUpperCase(op)) {
                case 'M' -> {
                    cx = a[0] + ox;
                    cy = a[1] + oy;
                    sx = cx;
                    sy = cy;
                    b.moveTo(cx, cy);
                    rcx = cx;
                    rcy = cy;
                }
                case 'L' -> {
                    cx = a[0] + ox;
                    cy = a[1] + oy;
                    b.lineTo(cx, cy);
                    rcx = cx;
                    rcy = cy;
                }
                case 'H' -> {
                    cx = a[0] + ox;
                    b.lineTo(cx, cy);
                    rcx = cx;
                    rcy = cy;
                }
                case 'V' -> {
                    cy = a[0] + oy;
                    b.lineTo(cx, cy);
                    rcx = cx;
                    rcy = cy;
                }
                case 'C' -> {
                    float x1 = a[0] + ox;
                    float y1 = a[1] + oy;
                    float x2 = a[2] + ox;
                    float y2 = a[3] + oy;
                    float x3 = a[4] + ox;
                    float y3 = a[5] + oy;
                    b.cubicTo(cx, cy, x1, y1, x2, y2, x3, y3);
                    rcx = x2;
                    rcy = y2;
                    cx = x3;
                    cy = y3;
                }
                case 'S' -> {
                    // The first control point mirrors the previous curve's last one.
                    boolean smooth = prev == 'C' || prev == 'c' || prev == 'S' || prev == 's';
                    float x1 = smooth ? 2 * cx - rcx : cx;
                    float y1 = smooth ? 2 * cy - rcy : cy;
                    float x2 = a[0] + ox;
                    float y2 = a[1] + oy;
                    float x3 = a[2] + ox;
                    float y3 = a[3] + oy;
                    b.cubicTo(cx, cy, x1, y1, x2, y2, x3, y3);
                    rcx = x2;
                    rcy = y2;
                    cx = x3;
                    cy = y3;
                }
                case 'Q' -> {
                    float qx = a[0] + ox;
                    float qy = a[1] + oy;
                    float x2 = a[2] + ox;
                    float y2 = a[3] + oy;
                    b.quadTo(cx, cy, qx, qy, x2, y2);
                    rcx = qx;
                    rcy = qy;
                    cx = x2;
                    cy = y2;
                }
                case 'T' -> {
                    boolean smooth = prev == 'Q' || prev == 'q' || prev == 'T' || prev == 't';
                    float qx = smooth ? 2 * cx - rcx : cx;
                    float qy = smooth ? 2 * cy - rcy : cy;
                    float x2 = a[0] + ox;
                    float y2 = a[1] + oy;
                    b.quadTo(cx, cy, qx, qy, x2, y2);
                    rcx = qx;
                    rcy = qy;
                    cx = x2;
                    cy = y2;
                }
                case 'Z' -> {
                    b.close();
                    cx = sx;
                    cy = sy;
                    rcx = cx;
                    rcy = cy;
                }
                default -> {
                    // Unsupported command (A): ignore it rather than corrupting the rest of the path.
                }
            }
            prev = op;
        }
        b.finish();
    }

    // ------------------------------------------------------------------
    // Contour assembly
    // ------------------------------------------------------------------

    /** Collects transformed points into contours, splitting on every moveto and every {@code Z}. */
    private static final class Builder {

        private final Transform transform;
        private final List<Contour> out;
        private float[] buf = new float[64];
        private int size;
        private boolean open;

        Builder(Transform transform, List<Contour> out) {
            this.transform = transform;
            this.out = out;
        }

        void moveTo(float x, float y) {
            emit(false);
            add(x, y);
            open = true;
        }

        void lineTo(float x, float y) {
            if (!open) {
                moveTo(x, y);
                return;
            }
            add(x, y);
        }

        void quadTo(float x0, float y0, float qx, float qy, float x1, float y1) {
            // Degree-elevate to a cubic – one flattening path for both curve types.
            cubicTo(x0, y0,
                    x0 + 2F / 3F * (qx - x0), y0 + 2F / 3F * (qy - y0),
                    x1 + 2F / 3F * (qx - x1), y1 + 2F / 3F * (qy - y1),
                    x1, y1);
        }

        void cubicTo(float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3) {
            if (!open) {
                moveTo(x0, y0);
            }
            // Transform the control points first, then subdivide: an affine transform commutes with
            // Bezier evaluation, so this is exact - and it lets the step count follow device size.
            float dx0 = transform.px(x0, y0);
            float dy0 = transform.py(x0, y0);
            float dx1 = transform.px(x1, y1);
            float dy1 = transform.py(x1, y1);
            float dx2 = transform.px(x2, y2);
            float dy2 = transform.py(x2, y2);
            float dx3 = transform.px(x3, y3);
            float dy3 = transform.py(x3, y3);

            float hull = dist(dx0, dy0, dx1, dy1) + dist(dx1, dy1, dx2, dy2) + dist(dx2, dy2, dx3, dy3);
            int steps = Math.clamp((int) Math.ceil(hull / FLATNESS), MIN_STEPS, MAX_STEPS);
            for (int i = 1; i <= steps; i++) {
                float t = (float) i / steps;
                float u = 1 - t;
                float w0 = u * u * u;
                float w1 = 3 * u * u * t;
                float w2 = 3 * u * t * t;
                float w3 = t * t * t;
                addDevice(w0 * dx0 + w1 * dx1 + w2 * dx2 + w3 * dx3,
                        w0 * dy0 + w1 * dy1 + w2 * dy2 + w3 * dy3);
            }
        }

        void close() {
            emit(true);
        }

        void finish() {
            emit(false);
        }

        private void add(float x, float y) {
            addDevice(transform.px(x, y), transform.py(x, y));
        }

        private void addDevice(float x, float y) {
            if (size + 2 > buf.length) {
                buf = Arrays.copyOf(buf, buf.length * 2);
            }
            buf[size++] = x;
            buf[size++] = y;
        }

        private void emit(boolean closed) {
            // Two points are kept: a bare line encloses no area and so contributes nothing to a
            // fill, but it is a perfectly good STROKE - the stems of I/K/Y and the crossbars of
            // E/F are exactly that, and dropping them silently deletes half the alphabet.
            if (size >= 4) {
                out.add(new Contour(Arrays.copyOf(buf, size), closed));
            }
            size = 0;
            open = false;
        }

        private static float dist(float ax, float ay, float bx, float by) {
            float dx = bx - ax;
            float dy = by - ay;
            return (float) Math.sqrt(dx * dx + dy * dy);
        }
    }

    // ------------------------------------------------------------------
    // Tokenizer
    // ------------------------------------------------------------------

    private static boolean isCommand(char c) {
        return switch (Character.toUpperCase(c)) {
            case 'M', 'L', 'H', 'V', 'C', 'S', 'Q', 'T', 'A', 'Z' -> true;
            default -> false;
        };
    }

    private static int argCount(char op) {
        return switch (Character.toUpperCase(op)) {
            case 'M', 'L', 'T' -> 2;
            case 'H', 'V' -> 1;
            case 'C' -> 6;
            case 'S', 'Q' -> 4;
            case 'A' -> 7;
            default -> 0;
        };
    }

    private static int skipSeparators(String s, int i) {
        while (i < s.length() && (s.charAt(i) == ',' || s.charAt(i) <= ' ')) {
            i++;
        }
        return i;
    }

    /** End index of the number starting at {@code i}, or {@code i} itself when there is none. */
    private static int numberEnd(String s, int i) {
        int n = s.length();
        int at = i;
        if (at < n && (s.charAt(at) == '+' || s.charAt(at) == '-')) {
            at++;
        }
        int digits = 0;
        while (at < n && Character.isDigit(s.charAt(at))) {
            at++;
            digits++;
        }
        if (at < n && s.charAt(at) == '.') {
            at++;
            while (at < n && Character.isDigit(s.charAt(at))) {
                at++;
                digits++;
            }
        }
        if (digits == 0) {
            return i;
        }
        if (at < n && (s.charAt(at) == 'e' || s.charAt(at) == 'E')) {
            int mark = at++;
            if (at < n && (s.charAt(at) == '+' || s.charAt(at) == '-')) {
                at++;
            }
            int expDigits = 0;
            while (at < n && Character.isDigit(s.charAt(at))) {
                at++;
                expDigits++;
            }
            if (expDigits == 0) {
                at = mark; // "1e" – the exponent was not really there.
            }
        }
        return at;
    }
}
