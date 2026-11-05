/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import org.joml.Vector4f;

/**
 * Drawing world-space geometry through the HUD pipeline.
 *
 * <p>1.26.2's world renderer is GPU-buffer based and no longer exposes a classic {@code renderLineBox},
 * so instead of a world-render mixin we project each world point to the screen ourselves – exactly
 * like vanilla's {@code GameRenderer.projectPointToScreen} – and draw through the proven
 * {@link GuiGraphicsExtractor}. The projection is done by hand so the clip-space {@code w} is
 * readable and points behind the camera can be culled.
 *
 * <p>Unlike the equivalent helpers inside {@code DungeonHighlight}, colour <i>and</i> line thickness are
 * parameters here, which is what the configurable Ether Warp highlight needs.
 *
 * <p>Render thread only. The projection works in static scratch buffers, so the per-frame path
 * allocates nothing - a long route drawn every frame used to create several objects per node.
 */
public final class WorldRender {

    private WorldRender() {
    }

    /**
     * How far in front of the camera the near plane sits for clipping. Anything closer projects to
     * absurd coordinates, so geometry is cut here rather than followed to infinity.
     */
    private static final float NEAR = 0.05f;

    /**
     * Screen coordinates of single projected POINTS (labels, markers) are clamped to this multiple
     * of the viewport - a label position a million pixels out is useless. Line segments are never
     * clamped this way: their off-screen ends are {@linkplain #clip2d clipped along the line}, since
     * clamping an endpoint sideways bends the whole visible edge.
     */
    private static final int OFFSCREEN_LIMIT = 4;

    /**
     * How far past the screen edge line endpoints may keep drawing before the exact 2D clip cuts
     * them. A small margin, so a thick line's rotated fill never pops at the border.
     */
    private static final float EDGE_MARGIN = 64f;

    /** {@link #projectSegment}: nothing of the segment is on screen. */
    public static final int SEGMENT_CULLED = 0;
    /** {@link #projectSegment}: both ends in front of the camera, each projected as a point is. */
    public static final int SEGMENT_FRONT = 1;
    /** {@link #projectSegment}: one end was behind the camera and was cut back to the near plane. */
    public static final int SEGMENT_NEAR_CLIPPED = 2;

    // Scratch, render thread only. No buffer is held across a call that could reach it again.
    private static final Vector4f PROJECT = new Vector4f();
    private static final Vector4f LINE_A = new Vector4f();
    private static final Vector4f LINE_B = new Vector4f();
    private static final Vector4f NEAR_A = new Vector4f();
    private static final Vector4f NEAR_B = new Vector4f();
    private static final Vector4f[] CORNERS = vectors(8);
    private static final Vector4f[] CULL_CORNERS = vectors(8);
    private static final Vector4f[] POLY = vectors(5);
    private static final float[][] POLY_SCREEN = new float[5][2];
    private static final float[] DIVIDED = new float[2];
    private static final float[] PA = new float[2];
    private static final float[] PB = new float[2];
    private static final float[] CLIP_P = new float[4];
    private static final float[] CLIP_Q = new float[4];
    private static final int[] POINT = new int[2];
    private static final int[] SEGMENT = new int[4];

    /** The 12 edges of a box whose 8 corners are indexed by bits x,z,y (bit 0 = x, 1 = z, 2 = y). */
    private static final int[][] BOX_EDGES = {
            {0, 1}, {1, 3}, {3, 2}, {2, 0}, // bottom
            {4, 5}, {5, 7}, {7, 6}, {6, 4}, // top
            {0, 4}, {1, 5}, {2, 6}, {3, 7}, // verticals
    };

    private static Vector4f[] vectors(int count) {
        Vector4f[] vectors = new Vector4f[count];
        for (int i = 0; i < count; i++) {
            vectors[i] = new Vector4f();
        }
        return vectors;
    }

    /**
     * Projects a world point to GUI-scaled screen pixels: writes the screen point into {@code out}, or
     * returns {@code false} and leaves {@code out} untouched when the point is behind the camera.
     */
    public static boolean project(Matrix4fc viewProjection, double camX, double camY, double camZ,
                                  double x, double y, double z, int guiWidth, int guiHeight, int[] out) {
        Vector4f clip = toClip(viewProjection, camX, camY, camZ, x, y, z, PROJECT);
        if (clip.w <= NEAR) {
            return false;
        }
        divide(clip, guiWidth, guiHeight, out);
        return true;
    }

    /**
     * The world point in clip space, written into {@code out}, where {@code w} is its depth in front
     * of the camera. The camera is subtracted in double before narrowing to float, so a point far
     * from the world origin keeps its precision.
     */
    public static Vector4f toClip(Matrix4fc viewProjection, double camX, double camY, double camZ,
                                  double x, double y, double z, Vector4f out) {
        out.set((float) (x - camX), (float) (y - camY), (float) (z - camZ), 1.0f);
        return viewProjection.transform(out);
    }

    /** Perspective divide + viewport transform, with the off-screen POINT clamp applied. */
    private static void divide(Vector4f clip, int guiWidth, int guiHeight, int[] out) {
        divideF(clip, guiWidth, guiHeight, DIVIDED);
        int limitX = guiWidth * OFFSCREEN_LIMIT;
        int limitY = guiHeight * OFFSCREEN_LIMIT;
        out[0] = Math.round(Math.max(-limitX, Math.min(limitX, DIVIDED[0])));
        out[1] = Math.round(Math.max(-limitY, Math.min(limitY, DIVIDED[1])));
    }

    /**
     * Perspective divide + viewport transform, unclamped. Anything that is one END of a segment must
     * come through here and be {@linkplain #clip2d clipped}, never through the clamped {@link #divide}:
     * clamping X and Y independently drags an off-screen endpoint sideways off the true line, and the
     * whole visible edge then swings around as the camera turns (the "borders dismorph into weird
     * shapes when you are not looking at the correct side" bug - a point just in front of the near
     * plane projects tens of thousands of pixels out, where the old clamp bent it hardest).
     */
    private static void divideF(Vector4f clip, int guiWidth, int guiHeight, float[] out) {
        out[0] = (clip.x / clip.w * 0.5f + 0.5f) * guiWidth;
        out[1] = (0.5f - clip.y / clip.w * 0.5f) * guiHeight;
    }

    /**
     * Whether a clip-space segment lies wholly beyond one side of the screen, widened by
     * {@link #EDGE_MARGIN} pixels so a thick line or a glow just past the border is never cut.
     *
     * <p>Tested in homogeneous coordinates ({@code x > k*w}) rather than on divided screen points.
     * Each test is a half-space bounded by a plane through the eye, so it holds for points behind the
     * camera too, where a perspective divide would flip the sign: when both ends lie in the same
     * outside half-space, every point between them does as well. Only the four side planes are
     * tested; the near plane is clipped against, never culled on.
     */
    private static boolean outsideOneSide(Vector4f a, Vector4f b, int guiWidth, int guiHeight) {
        float kx = 1f + 2f * EDGE_MARGIN / guiWidth;
        float ky = 1f + 2f * EDGE_MARGIN / guiHeight;
        return (a.x < -kx * a.w && b.x < -kx * b.w)
                || (a.x > kx * a.w && b.x > kx * b.w)
                || (a.y < -ky * a.w && b.y < -ky * b.w)
                || (a.y > ky * a.w && b.y > ky * b.w);
    }

    /**
     * Whether any part of an axis-aligned world box can land on screen, widened by
     * {@code marginPx} GUI pixels on every side. Tested on the 8 clip-space corners before any
     * divide, so a culled marker costs eight transforms and no projection, label or occlusion work.
     *
     * <p>Conservative: {@code false} only when all corners sit behind the near plane or all lie
     * beyond the same side plane. A box that slips past every single plane (a big box diagonal to a
     * screen corner) is kept and left to the per-edge clip - never the other way round, so nothing
     * that would have drawn goes missing.
     *
     * <p>Tracers and off-screen arrows point at targets that are off screen on purpose, so they
     * never call this; that is the opt-out.
     */
    public static boolean boxInView(Matrix4fc viewProjection, double camX, double camY, double camZ,
                                    double x0, double y0, double z0, double x1, double y1, double z1,
                                    int guiWidth, int guiHeight, float marginPx) {
        Vector4f[] corners = CULL_CORNERS;
        for (int corner = 0; corner < 8; corner++) {
            toClip(viewProjection, camX, camY, camZ,
                    (corner & 1) == 0 ? x0 : x1, (corner & 4) == 0 ? y0 : y1, (corner & 2) == 0 ? z0 : z1,
                    corners[corner]);
        }
        return !cornersOutside(corners, 1f + 2f * marginPx / guiWidth, 1f + 2f * marginPx / guiHeight);
    }

    /** {@link #boxInView} with the default {@link #EDGE_MARGIN}, matching what the edge clip keeps. */
    public static boolean boxInView(Matrix4fc viewProjection, double camX, double camY, double camZ,
                                    double x0, double y0, double z0, double x1, double y1, double z1,
                                    int guiWidth, int guiHeight) {
        return boxInView(viewProjection, camX, camY, camZ, x0, y0, z0, x1, y1, z1,
                guiWidth, guiHeight, EDGE_MARGIN);
    }

    /**
     * The rejection itself: all 8 clip-space corners behind the near plane, or all beyond one side
     * plane widened to {@code x = ±kx*w}, {@code y = ±ky*w}. Side planes pass through the eye, so
     * the test holds for corners behind the camera too (see {@link #outsideOneSide}).
     */
    static boolean cornersOutside(Vector4f[] c, float kx, float ky) {
        int behind = 0;
        int left = 0;
        int right = 0;
        int below = 0;
        int above = 0;
        for (int i = 0; i < 8; i++) {
            Vector4f v = c[i];
            if (v.w <= NEAR) {
                behind++;
            }
            if (v.x < -kx * v.w) {
                left++;
            } else if (v.x > kx * v.w) {
                right++;
            }
            if (v.y < -ky * v.w) {
                below++;
            } else if (v.y > ky * v.w) {
                above++;
            }
        }
        return behind == 8 || left == 8 || right == 8 || below == 8 || above == 8;
    }

    /**
     * Projects one segment of a polyline whose points are already in clip space ({@link #toClip})
     * into {@code out} as {@code x0, y0, x1, y1}. Neither input is changed.
     *
     * <p>Built for long polylines: the caller transforms each point once and passes consecutive
     * pairs, and a segment wholly beyond one side of the screen is rejected before any divide.
     *
     * <ul>
     *   <li>{@link #SEGMENT_FRONT} - both ends in front: each end is projected exactly as
     *       {@link #project} projects a point, so a polyline drawn from these matches one drawn from
     *       per-point projections pixel for pixel.</li>
     *   <li>{@link #SEGMENT_NEAR_CLIPPED} - one end behind the camera: that end is moved along the
     *       segment to the near plane and the rest is clipped to the screen as {@link #line3d} does.
     *       A line running past the player into view stays drawn instead of disappearing until both
     *       of its ends are in front.</li>
     *   <li>{@link #SEGMENT_CULLED} - both ends behind, both beyond one side, or nothing left after
     *       the screen clip; {@code out} is then unspecified.</li>
     * </ul>
     */
    public static int projectSegment(Vector4f a, Vector4f b, int guiWidth, int guiHeight, int[] out) {
        boolean aBehind = a.w <= NEAR;
        boolean bBehind = b.w <= NEAR;
        if ((aBehind && bBehind) || outsideOneSide(a, b, guiWidth, guiHeight)) {
            return SEGMENT_CULLED;
        }
        if (!aBehind && !bBehind) {
            divide(a, guiWidth, guiHeight, POINT);
            out[0] = POINT[0];
            out[1] = POINT[1];
            divide(b, guiWidth, guiHeight, POINT);
            out[2] = POINT[0];
            out[3] = POINT[1];
            return SEGMENT_FRONT;
        }
        return clippedToScreen(a, b, guiWidth, guiHeight, out) ? SEGMENT_NEAR_CLIPPED : SEGMENT_CULLED;
    }

    /**
     * The visible part of a clip-space segment with at least one end in front of the camera: an end
     * behind is cut back to the near plane, then the exact 2D clip trims the off-screen length along
     * the line. Writes {@code x0, y0, x1, y1}; {@code false} when nothing is left.
     */
    private static boolean clippedToScreen(Vector4f a, Vector4f b, int guiWidth, int guiHeight,
                                           int[] out) {
        Vector4f ca = a;
        Vector4f cb = b;
        if (a.w <= NEAR) {
            ca = clipToNear(a, b, NEAR_A);
        } else if (b.w <= NEAR) {
            cb = clipToNear(b, a, NEAR_B);
        }
        // Unclamped endpoints, then the exact 2D clip: the off-screen part is cut along the line,
        // so the visible part keeps its true direction however far the far end projects out.
        divideF(ca, guiWidth, guiHeight, PA);
        divideF(cb, guiWidth, guiHeight, PB);
        if (!clip2d(PA, PB, -EDGE_MARGIN, -EDGE_MARGIN, guiWidth + EDGE_MARGIN, guiHeight + EDGE_MARGIN)) {
            return false;
        }
        out[0] = Math.round(PA[0]);
        out[1] = Math.round(PA[1]);
        out[2] = Math.round(PB[0]);
        out[3] = Math.round(PB[1]);
        return true;
    }

    /**
     * Clips the 2D segment {@code a→b} (mutated in place) to the rectangle, moving each end
     * <b>along the segment</b> to where it crosses the border - the direction is exact, only the
     * off-screen length is cut. Returns {@code false} when nothing of it lies inside.
     */
    private static boolean clip2d(float[] a, float[] b,
                                  float minX, float minY, float maxX, float maxY) {
        float dx = b[0] - a[0];
        float dy = b[1] - a[1];
        float t0 = 0f;
        float t1 = 1f;
        float[] p = CLIP_P;
        float[] q = CLIP_Q;
        p[0] = -dx;
        p[1] = dx;
        p[2] = -dy;
        p[3] = dy;
        q[0] = a[0] - minX;
        q[1] = maxX - a[0];
        q[2] = a[1] - minY;
        q[3] = maxY - a[1];
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0f) {
                if (q[i] < 0f) {
                    return false;   // parallel to this border and fully outside it
                }
                continue;
            }
            float r = q[i] / p[i];
            if (p[i] < 0f) {
                if (r > t1) {
                    return false;
                }
                if (r > t0) {
                    t0 = r;
                }
            } else {
                if (r < t0) {
                    return false;
                }
                if (r < t1) {
                    t1 = r;
                }
            }
        }
        float ax = a[0];
        float ay = a[1];
        a[0] = ax + t0 * dx;
        a[1] = ay + t0 * dy;
        b[0] = ax + t1 * dx;
        b[1] = ay + t1 * dy;
        return true;
    }

    /**
     * Writes into {@code out} the clip-space point where the segment from {@code behind} (behind the
     * near plane) towards {@code inside} crosses that plane. {@code out} must be neither input.
     */
    private static Vector4f clipToNear(Vector4f behind, Vector4f inside, Vector4f out) {
        float t = (NEAR - behind.w) / (inside.w - behind.w);
        return out.set(
                behind.x + (inside.x - behind.x) * t,
                behind.y + (inside.y - behind.y) * t,
                behind.z + (inside.z - behind.z) * t,
                NEAR);
    }

    /**
     * A world-space line segment, <b>clipped</b> to the near plane rather than dropped when one end
     * is behind the camera.
     *
     * <p>This is the difference between an outline that stays put and one that flickers: a box you
     * stand inside always has corners behind you, and discarding those edges makes the box appear to
     * come apart and reassemble as you turn. Clipping keeps the visible part of every edge on screen,
     * so the shape is stable no matter where you stand in it.
     */
    public static void line3d(GuiGraphicsExtractor g, Matrix4fc viewProjection, Vec3 camPos,
                              Vec3 a, Vec3 b, int color, int thickness) {
        toClip(viewProjection, camPos.x, camPos.y, camPos.z, a.x, a.y, a.z, LINE_A);
        toClip(viewProjection, camPos.x, camPos.y, camPos.z, b.x, b.y, b.z, LINE_B);
        clipLine(g, LINE_A, LINE_B, color, thickness);
    }

    /** {@link #line3d} for two points already in clip space. */
    private static void clipLine(GuiGraphicsExtractor g, Vector4f a, Vector4f b, int color, int thickness) {
        if (a.w <= NEAR && b.w <= NEAR) {
            return;   // wholly behind the camera - nothing of it is on screen
        }
        int[] s = SEGMENT;
        if (clippedToScreen(a, b, g.guiWidth(), g.guiHeight(), s)) {
            line(g, s[0], s[1], s[2], s[3], color, thickness);
        }
    }

    /**
     * A solid line between two screen points: one filled rectangle rotated to the segment's angle
     * via the pose stack, so an edge costs a single {@code fill} regardless of its length.
     */
    public static void line(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1,
                            int color, int thickness) {
        int t = Math.max(1, thickness);
        int half = t / 2;
        float dx = x1 - x0;
        float dy = y1 - y0;
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length < 1.0f) {
            g.fill(x0 - half, y0 - half, x0 + t - half, y0 + t - half, color);
            return;
        }
        org.joml.Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        pose.translate(x0, y0);
        pose.rotate((float) Math.atan2(dy, dx));
        g.fill(0, -half, Math.round(length), t - half, color);
        pose.popMatrix();
    }

    /**
     * Fills a translucent silhouette of an axis-aligned world box: projects its 8 corners and fills
     * their screen-space bounding rectangle. Approximate on purpose – it reads as a translucent
     * "ghost" cube without needing a per-face quad fill the HUD pipeline cannot do. Skipped entirely
     * when any corner is behind the camera, so a cube straddling the view never fills the screen.
     */
    public static void fillBox(GuiGraphicsExtractor g, Matrix4fc viewProjection, Vec3 camPos,
                               double x0, double y0, double z0, double x1, double y1, double z1,
                               int color) {
        int gw = g.guiWidth();
        int gh = g.guiHeight();
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        if (!boxInView(viewProjection, camPos.x, camPos.y, camPos.z, x0, y0, z0, x1, y1, z1, gw, gh)) {
            return; // the filled rectangle would be clamped away to nothing anyway
        }
        int[] p = POINT;
        for (int corner = 0; corner < 8; corner++) {
            if (!project(viewProjection, camPos.x, camPos.y, camPos.z,
                    (corner & 1) == 0 ? x0 : x1, (corner & 4) == 0 ? y0 : y1, (corner & 2) == 0 ? z0 : z1,
                    gw, gh, p)) {
                return; // partially behind the camera - don't fill a bogus rectangle
            }
            minX = Math.min(minX, p[0]);
            minY = Math.min(minY, p[1]);
            maxX = Math.max(maxX, p[0]);
            maxY = Math.max(maxY, p[1]);
        }
        // Clamp to the screen so a near cube cannot fill far past the viewport.
        minX = Math.max(0, minX);
        minY = Math.max(0, minY);
        maxX = Math.min(gw, maxX);
        maxY = Math.min(gh, maxY);
        if (maxX > minX && maxY > minY) {
            g.fill(minX, minY, maxX, maxY, color);
        }
    }

    /**
     * Fills one flat world-space quad - a wall - by scan-filling its projected shape in vertical
     * strips.
     *
     * <p>{@link #fillBox} cannot do this: it fills the screen-space bounding rectangle of a whole
     * box, which reads fine for a mob-sized cube but would cover half the screen for something like
     * a 96-block plot. The HUD pipeline has no textured 3D quad either, so the shape is filled the
     * one way that is available - project the four corners, then walk across the projected polygon
     * and fill a column at a time. Strips are a few pixels wide because the cost is one
     * {@code fill} per strip and the edge stepping is invisible at a translucent alpha.
     *
     * <p>Corners behind the camera are <b>clipped</b> against the near plane, not dropped: a wall you
     * are standing next to always has some, and discarding it made the tint blink out exactly when
     * you were close enough to care. Clipping can turn the quad into a five-sided polygon, which the
     * scan fill handles because it works off the edge list rather than assuming four corners.
     */
    public static void fillQuad(GuiGraphicsExtractor g, Matrix4fc viewProjection, Vec3 camPos,
                                Vec3 a, Vec3 b, Vec3 c, Vec3 d, int color, int strip) {
        // Sutherland-Hodgman against the single near plane, in clip space. Four corners against one
        // plane give at most five vertices.
        Vector4f[] corners = CORNERS;
        toClip(viewProjection, camPos.x, camPos.y, camPos.z, a.x, a.y, a.z, corners[0]);
        toClip(viewProjection, camPos.x, camPos.y, camPos.z, b.x, b.y, b.z, corners[1]);
        toClip(viewProjection, camPos.x, camPos.y, camPos.z, c.x, c.y, c.z, corners[2]);
        toClip(viewProjection, camPos.x, camPos.y, camPos.z, d.x, d.y, d.z, corners[3]);
        int count = 0;
        for (int i = 0; i < 4; i++) {
            Vector4f current = corners[i];
            Vector4f next = corners[(i + 1) % 4];
            boolean currentIn = current.w > NEAR;
            boolean nextIn = next.w > NEAR;
            if (currentIn) {
                POLY[count++].set(current);
            }
            if (currentIn != nextIn) {
                if (currentIn) {
                    clipToNear(next, current, POLY[count++]);
                } else {
                    clipToNear(current, next, POLY[count++]);
                }
            }
        }
        if (count < 3) {
            return;   // wholly behind the camera
        }

        int gw = g.guiWidth();
        int gh = g.guiHeight();
        // Unclamped corners: the scan fill below cuts to the screen anyway, and the old point clamp
        // bent the projected edges - the tint's slant then disagreed with the outline drawn over it.
        float[][] p = POLY_SCREEN;
        float cornerMinX = Float.MAX_VALUE;
        float cornerMaxX = -Float.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            divideF(POLY[i], gw, gh, p[i]);
            cornerMinX = Math.min(cornerMinX, p[i][0]);
            cornerMaxX = Math.max(cornerMaxX, p[i][0]);
        }
        int minX = (int) Math.max(0, cornerMinX);
        int maxX = (int) Math.min(gw, cornerMaxX);
        int step = Math.max(1, strip);
        for (int x = minX; x < maxX; x += step) {
            double sample = x + step / 2.0;
            double top = Double.MAX_VALUE;
            double bottom = -Double.MAX_VALUE;
            for (int i = 0; i < count; i++) {
                float[] p1 = p[i];
                float[] p2 = p[(i + 1) % count];
                double x1 = p1[0];
                double x2 = p2[0];
                if ((sample < x1 && sample < x2) || (sample >= x1 && sample >= x2)) {
                    continue;   // this edge does not span the sampled column
                }
                double t = (sample - x1) / (x2 - x1);
                double y = p1[1] + t * (p2[1] - p1[1]);
                top = Math.min(top, y);
                bottom = Math.max(bottom, y);
            }
            if (bottom <= top) {
                continue;
            }
            int y0 = (int) Math.max(0, Math.round(top));
            int y1 = (int) Math.min(gh, Math.round(bottom));
            if (y1 > y0) {
                g.fill(x, y0, Math.min(maxX, x + step), y1, color);
            }
        }
    }

    /**
     * A tracer: the line from the player out to a highlighted target, so a box you cannot see yet
     * still tells you which way to walk. Shared by every SBS highlight, each behind its own toggle.
     *
     * <p>The player's end of the line cannot be projected like any other world point – the eyes sit
     * <i>on</i> the camera, so the vector to project is very nearly zero and the clip-space {@code w}
     * lands on the wrong side of the near-plane cull; the line then either vanishes or shoots off
     * from a meaningless corner. The centre of the screen is where the eyes project to by definition,
     * so it is used directly and the tracer always starts at the crosshair. Skipped silently while
     * the target is behind the camera.
     */
    public static void tracer(GuiGraphicsExtractor g, Matrix4fc viewProjection, Vec3 camPos,
                              Vec3 target, int color, int thickness) {
        int gw = g.guiWidth();
        int gh = g.guiHeight();
        Vector4f clip = toClip(viewProjection, camPos.x, camPos.y, camPos.z,
                target.x, target.y, target.z, PROJECT);
        if (clip.w <= NEAR) {
            return;
        }
        // Same rule as line3d: the far end is clipped along the line, never clamped sideways - a
        // clamped target made the tracer point in a subtly wrong direction while it was off screen.
        float[] from = PA;
        float[] to = PB;
        from[0] = gw / 2f;
        from[1] = gh / 2f;
        divideF(clip, gw, gh, to);
        if (clip2d(from, to, -EDGE_MARGIN, -EDGE_MARGIN, gw + EDGE_MARGIN, gh + EDGE_MARGIN)) {
            line(g, Math.round(from[0]), Math.round(from[1]), Math.round(to[0]), Math.round(to[1]),
                    color, Math.max(1, thickness));
        }
    }

    /** The tracer target for a highlight box: its centre, which reads best at any distance. */
    public static void tracerToBox(GuiGraphicsExtractor g, Matrix4fc viewProjection, Vec3 camPos,
                                   net.minecraft.world.phys.AABB box, int color, int thickness) {
        tracer(g, viewProjection, camPos, box.getCenter(), color, thickness);
    }

    /** Projects and draws the 12 edges of an axis-aligned world-space box. */
    public static void boxEdges(GuiGraphicsExtractor g, Matrix4fc viewProjection, Vec3 camPos,
                                double x0, double y0, double z0, double x1, double y1, double z1,
                                int color, int thickness) {
        boxEdges(g, viewProjection, camPos.x, camPos.y, camPos.z, x0, y0, z0, x1, y1, z1, color, thickness);
    }

    /**
     * {@link #boxEdges} with the camera as primitives. Each corner is transformed once and shared by
     * its three edges.
     */
    public static void boxEdges(GuiGraphicsExtractor g, Matrix4fc viewProjection,
                                double camX, double camY, double camZ,
                                double x0, double y0, double z0, double x1, double y1, double z1,
                                int color, int thickness) {
        Vector4f[] corners = CORNERS;
        for (int corner = 0; corner < 8; corner++) {
            toClip(viewProjection, camX, camY, camZ,
                    (corner & 1) == 0 ? x0 : x1, (corner & 4) == 0 ? y0 : y1, (corner & 2) == 0 ? z0 : z1,
                    corners[corner]);
        }
        // A box wholly beyond one screen side or behind the camera has no edge left to draw.
        if (cornersOutside(corners, 1f + 2f * EDGE_MARGIN / g.guiWidth(), 1f + 2f * EDGE_MARGIN / g.guiHeight())) {
            return;
        }
        // Each edge is clipped individually: a box you stand inside has corners behind you, and
        // dropping those edges is what made outlines come apart and snap back as you turned.
        for (int[] e : BOX_EDGES) {
            clipLine(g, corners[e[0]], corners[e[1]], color, thickness);
        }
    }
}
