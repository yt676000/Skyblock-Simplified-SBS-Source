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
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;

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

    /** Projects a world point to GUI-scaled screen pixels, or {@code null} when behind the camera. */
    public static int[] projectToScreen(Matrix4f viewProjection, Vec3 camPos, Vec3 world,
                                        int guiWidth, int guiHeight) {
        Vector4f clip = toClip(viewProjection, camPos, world);
        return clip.w <= NEAR ? null : divide(clip, guiWidth, guiHeight);
    }

    /** The world point in clip space, where {@code w} is its depth in front of the camera. */
    private static Vector4f toClip(Matrix4f viewProjection, Vec3 camPos, Vec3 world) {
        Vec3 rel = world.subtract(camPos);
        return viewProjection.transform(
                new Vector4f((float) rel.x, (float) rel.y, (float) rel.z, 1.0f));
    }

    /** Perspective divide + viewport transform, with the off-screen POINT clamp applied. */
    private static int[] divide(Vector4f clip, int guiWidth, int guiHeight) {
        float[] s = divideF(clip, guiWidth, guiHeight);
        int limitX = guiWidth * OFFSCREEN_LIMIT;
        int limitY = guiHeight * OFFSCREEN_LIMIT;
        return new int[] {
                Math.round(Math.max(-limitX, Math.min(limitX, s[0]))),
                Math.round(Math.max(-limitY, Math.min(limitY, s[1]))),
        };
    }

    /**
     * Perspective divide + viewport transform, unclamped. Anything that is one END of a segment must
     * come through here and be {@linkplain #clip2d clipped}, never through the clamped {@link #divide}:
     * clamping X and Y independently drags an off-screen endpoint sideways off the true line, and the
     * whole visible edge then swings around as the camera turns (the "borders dismorph into weird
     * shapes when you are not looking at the correct side" bug - a point just in front of the near
     * plane projects tens of thousands of pixels out, where the old clamp bent it hardest).
     */
    private static float[] divideF(Vector4f clip, int guiWidth, int guiHeight) {
        return new float[] {
                (clip.x / clip.w * 0.5f + 0.5f) * guiWidth,
                (0.5f - clip.y / clip.w * 0.5f) * guiHeight,
        };
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
        float[] p = {-dx, dx, -dy, dy};
        float[] q = {a[0] - minX, maxX - a[0], a[1] - minY, maxY - a[1]};
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
     * Moves a clip-space point that sits behind the near plane forward along the segment towards
     * {@code inside}, to exactly where the segment crosses that plane.
     */
    private static Vector4f clipToNear(Vector4f behind, Vector4f inside) {
        float t = (NEAR - behind.w) / (inside.w - behind.w);
        return new Vector4f(
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
    public static void line3d(GuiGraphicsExtractor g, Matrix4f viewProjection, Vec3 camPos,
                              Vec3 a, Vec3 b, int color, int thickness) {
        Vector4f ca = toClip(viewProjection, camPos, a);
        Vector4f cb = toClip(viewProjection, camPos, b);
        boolean aBehind = ca.w <= NEAR;
        boolean bBehind = cb.w <= NEAR;
        if (aBehind && bBehind) {
            return;   // wholly behind the camera - nothing of it is on screen
        }
        if (aBehind) {
            ca = clipToNear(ca, cb);
        } else if (bBehind) {
            cb = clipToNear(cb, ca);
        }
        int gw = g.guiWidth();
        int gh = g.guiHeight();
        // Unclamped endpoints, then the exact 2D clip: the off-screen part is cut along the line,
        // so the visible part keeps its true direction however far the far end projects out.
        float[] pa = divideF(ca, gw, gh);
        float[] pb = divideF(cb, gw, gh);
        if (clip2d(pa, pb, -EDGE_MARGIN, -EDGE_MARGIN, gw + EDGE_MARGIN, gh + EDGE_MARGIN)) {
            line(g, Math.round(pa[0]), Math.round(pa[1]), Math.round(pb[0]), Math.round(pb[1]),
                    color, thickness);
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
    public static void fillBox(GuiGraphicsExtractor g, Matrix4f viewProjection, Vec3 camPos,
                               double x0, double y0, double z0, double x1, double y1, double z1,
                               int color) {
        Vec3[] corners = {
                new Vec3(x0, y0, z0), new Vec3(x1, y0, z0), new Vec3(x0, y0, z1), new Vec3(x1, y0, z1),
                new Vec3(x0, y1, z0), new Vec3(x1, y1, z0), new Vec3(x0, y1, z1), new Vec3(x1, y1, z1),
        };
        int gw = g.guiWidth();
        int gh = g.guiHeight();
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (Vec3 corner : corners) {
            int[] p = projectToScreen(viewProjection, camPos, corner, gw, gh);
            if (p == null) {
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
    public static void fillQuad(GuiGraphicsExtractor g, Matrix4f viewProjection, Vec3 camPos,
                                Vec3 a, Vec3 b, Vec3 c, Vec3 d, int color, int strip) {
        // Sutherland-Hodgman against the single near plane, in clip space.
        Vec3[] corners = {a, b, c, d};
        List<Vector4f> poly = new ArrayList<>(5);
        for (int i = 0; i < 4; i++) {
            Vector4f current = toClip(viewProjection, camPos, corners[i]);
            Vector4f next = toClip(viewProjection, camPos, corners[(i + 1) % 4]);
            boolean currentIn = current.w > NEAR;
            boolean nextIn = next.w > NEAR;
            if (currentIn) {
                poly.add(current);
            }
            if (currentIn != nextIn) {
                poly.add(currentIn ? clipToNear(next, current) : clipToNear(current, next));
            }
        }
        if (poly.size() < 3) {
            return;   // wholly behind the camera
        }

        int gw = g.guiWidth();
        int gh = g.guiHeight();
        // Unclamped corners: the scan fill below cuts to the screen anyway, and the old point clamp
        // bent the projected edges - the tint's slant then disagreed with the outline drawn over it.
        float[][] p = new float[poly.size()][];
        float cornerMinX = Float.MAX_VALUE;
        float cornerMaxX = -Float.MAX_VALUE;
        for (int i = 0; i < poly.size(); i++) {
            p[i] = divideF(poly.get(i), gw, gh);
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
            for (int i = 0; i < p.length; i++) {
                float[] p1 = p[i];
                float[] p2 = p[(i + 1) % p.length];
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
    public static void tracer(GuiGraphicsExtractor g, Matrix4f viewProjection, Vec3 camPos,
                              Vec3 target, int color, int thickness) {
        int gw = g.guiWidth();
        int gh = g.guiHeight();
        Vector4f clip = toClip(viewProjection, camPos, target);
        if (clip.w <= NEAR) {
            return;
        }
        // Same rule as line3d: the far end is clipped along the line, never clamped sideways - a
        // clamped target made the tracer point in a subtly wrong direction while it was off screen.
        float[] from = {gw / 2f, gh / 2f};
        float[] to = divideF(clip, gw, gh);
        if (clip2d(from, to, -EDGE_MARGIN, -EDGE_MARGIN, gw + EDGE_MARGIN, gh + EDGE_MARGIN)) {
            line(g, Math.round(from[0]), Math.round(from[1]), Math.round(to[0]), Math.round(to[1]),
                    color, Math.max(1, thickness));
        }
    }

    /** The tracer target for a highlight box: its centre, which reads best at any distance. */
    public static void tracerToBox(GuiGraphicsExtractor g, Matrix4f viewProjection, Vec3 camPos,
                                   net.minecraft.world.phys.AABB box, int color, int thickness) {
        tracer(g, viewProjection, camPos, box.getCenter(), color, thickness);
    }

    /** Projects and draws the 12 edges of an axis-aligned world-space box. */
    public static void boxEdges(GuiGraphicsExtractor g, Matrix4f viewProjection, Vec3 camPos,
                                double x0, double y0, double z0, double x1, double y1, double z1,
                                int color, int thickness) {
        // 8 corners, index bits = x,y,z.
        Vec3[] corners = {
                new Vec3(x0, y0, z0), new Vec3(x1, y0, z0), new Vec3(x0, y0, z1), new Vec3(x1, y0, z1),
                new Vec3(x0, y1, z0), new Vec3(x1, y1, z0), new Vec3(x0, y1, z1), new Vec3(x1, y1, z1),
        };
        int[][] edges = {
                {0, 1}, {1, 3}, {3, 2}, {2, 0}, // bottom
                {4, 5}, {5, 7}, {7, 6}, {6, 4}, // top
                {0, 4}, {1, 5}, {2, 6}, {3, 7}, // verticals
        };
        // Each edge is clipped individually: a box you stand inside has corners behind you, and
        // dropping those edges is what made outlines come apart and snap back as you turned.
        for (int[] e : edges) {
            line3d(g, viewProjection, camPos, corners[e[0]], corners[e[1]], color, thickness);
        }
    }
}
