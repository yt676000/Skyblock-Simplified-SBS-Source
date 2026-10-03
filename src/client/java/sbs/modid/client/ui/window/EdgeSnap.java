/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.window;

import java.util.List;

/**
 * The "Align" snapping shared by the editors: given a rectangle being dragged, its neighbours and the
 * screen it lives on, the correction that attaches it to the nearest of them.
 *
 * <p>Per neighbour and axis the candidates are <b>flush adjacency</b> (my right edge on its left edge
 * and vice versa – "directly next to it"; tops/bottoms likewise – "directly under it") and
 * <b>alignment</b> (same left / right / top / bottom edge, same centre). The smallest correction
 * within {@code range} wins its axis independently, so a box can sit flush below one neighbour while
 * being left-aligned to another.
 *
 * <p>The <b>screen</b> is a snap partner too, when one is given: each border (flush and at the safe
 * {@code margin} inset) and the screen centre line pull the box the same way a neighbour does. The two
 * axes still resolve independently, so a corner needs no special case – a box dragged into one is
 * caught by the horizontal border on one axis and the vertical border on the other, and lands in it.
 *
 * <p>The matched line comes back as a guide to draw, because "these two are attached" has to be
 * visible rather than guessed.
 */
public final class EdgeSnap {

    /** A screen-space rectangle in GUI-scaled pixels. */
    public record Rect(float x, float y, float w, float h) {
    }

    /**
     * The correction to apply, plus the guides that explain it.
     *
     * @param guideVertical   {@code {x, yFrom, yTo}} of the matched vertical line, or null
     * @param guideHorizontal {@code {y, xFrom, xTo}} of the matched horizontal line, or null
     */
    public record Result(double dx, double dy, float[] guideVertical, float[] guideHorizontal) {
    }

    /** How close a free-floating window comes to a border before {@link #toBorders} catches it. */
    public static final int WINDOW_RANGE = 6;

    private EdgeSnap() {
    }

    /** The snap against neighbours only – no border magnetism. */
    public static Result solve(Rect moving, List<Rect> others, int range) {
        return solve(moving, others, range, null, 0);
    }

    /**
     * Border magnetism for a free-floating window with no neighbours to align to: the top-left corner
     * moved onto the nearest border, safe inset or centre line, or left exactly where it was when none
     * is within {@link #WINDOW_RANGE}.
     *
     * <p>Dragging a window flush against an edge by hand means chasing single pixels through a
     * container screen; this makes the last few of them free.
     */
    public static int[] toBorders(int x, int y, int w, int h, int screenW, int screenH, int margin) {
        Result snap = solve(new Rect(x, y, w, h), List.of(), WINDOW_RANGE,
                new Rect(0, 0, screenW, screenH), margin);
        return snap == null
                ? new int[] {x, y}
                : new int[] {x + (int) Math.round(snap.dx()), y + (int) Math.round(snap.dy())};
    }

    /**
     * The snap for {@code moving} against {@code others} and the borders of {@code screen} (which may
     * be null), or null when nothing is within range.
     *
     * @param margin the safe inset from each border that is offered alongside the border itself
     */
    public static Result solve(Rect moving, List<Rect> others, int range, Rect screen, int margin) {
        Axis x = new Axis(range);
        Axis y = new Axis(range);
        for (Rect o : others) {
            x.offer(o.x() - (moving.x() + moving.w()), o);                    // flush: my right on its left
            x.offer((o.x() + o.w()) - moving.x(), o);                         // flush: my left on its right
            x.offer(o.x() - moving.x(), o);                                   // left edges aligned
            x.offer((o.x() + o.w()) - (moving.x() + moving.w()), o);          // right edges aligned
            x.offer((o.x() + o.w() / 2) - (moving.x() + moving.w() / 2), o);  // centres aligned

            y.offer(o.y() - (moving.y() + moving.h()), o);                    // flush: my bottom on its top
            y.offer((o.y() + o.h()) - moving.y(), o);                         // flush: my top on its bottom
            y.offer(o.y() - moving.y(), o);                                   // top edges aligned
            y.offer((o.y() + o.h()) - (moving.y() + moving.h()), o);          // bottom edges aligned
            y.offer((o.y() + o.h() / 2) - (moving.y() + moving.h() / 2), o);  // centres aligned
        }
        if (screen != null) {
            float left = screen.x();
            float right = screen.x() + screen.w();
            float top = screen.y();
            float bottom = screen.y() + screen.h();
            // A border target is a zero-width partner ON the line itself, so the guide it produces is
            // that line across the whole screen rather than an outline of the screen rectangle.
            offerLineX(x, moving, left, screen);
            offerLineX(x, moving, left + margin, screen);
            offerLineX(x, moving, right, screen);
            offerLineX(x, moving, right - margin, screen);
            x.offer(screen.x() + (screen.w() - moving.w()) / 2 - moving.x(),
                    vertical(screen.x() + screen.w() / 2, screen));

            offerLineY(y, moving, top, screen);
            offerLineY(y, moving, top + margin, screen);
            offerLineY(y, moving, bottom, screen);
            offerLineY(y, moving, bottom - margin, screen);
            y.offer(screen.y() + (screen.h() - moving.h()) / 2 - moving.y(),
                    horizontal(screen.y() + screen.h() / 2, screen));
        }
        if (x.partner == null && y.partner == null) {
            return null;
        }
        // Guides are measured from the SNAPPED rectangle and span both boxes, so the line drawn is
        // exactly the one the correction attached to.
        Rect s = new Rect((float) (moving.x() + x.delta), (float) (moving.y() + y.delta),
                moving.w(), moving.h());
        float[] guideVertical = x.partner == null ? null : new float[] {
                nearestLine(new float[] {s.x(), s.x() + s.w(), s.x() + s.w() / 2},
                        new float[] {x.partner.x(), x.partner.x() + x.partner.w(),
                                x.partner.x() + x.partner.w() / 2}),
                Math.min(s.y(), x.partner.y()) - 4,
                Math.max(s.y() + s.h(), x.partner.y() + x.partner.h()) + 4};
        float[] guideHorizontal = y.partner == null ? null : new float[] {
                nearestLine(new float[] {s.y(), s.y() + s.h(), s.y() + s.h() / 2},
                        new float[] {y.partner.y(), y.partner.y() + y.partner.h(),
                                y.partner.y() + y.partner.h() / 2}),
                Math.min(s.x(), y.partner.x()) - 4,
                Math.max(s.x() + s.w(), y.partner.x() + y.partner.w()) + 4};
        return new Result(x.delta, y.delta, guideVertical, guideHorizontal);
    }

    /** Both ways of meeting a vertical border line: my left edge on it, and my right edge on it. */
    private static void offerLineX(Axis axis, Rect moving, float lineX, Rect screen) {
        Rect partner = vertical(lineX, screen);
        axis.offer(lineX - moving.x(), partner);
        axis.offer(lineX - (moving.x() + moving.w()), partner);
    }

    private static void offerLineY(Axis axis, Rect moving, float lineY, Rect screen) {
        Rect partner = horizontal(lineY, screen);
        axis.offer(lineY - moving.y(), partner);
        axis.offer(lineY - (moving.y() + moving.h()), partner);
    }

    private static Rect vertical(float lineX, Rect screen) {
        return new Rect(lineX, screen.y(), 0, screen.h());
    }

    private static Rect horizontal(float lineY, Rect screen) {
        return new Rect(screen.x(), lineY, screen.w(), 0);
    }

    /** The best correction found so far on one axis, and the partner that produced it. */
    private static final class Axis {

        private final double range;
        private double delta;
        private double best;
        private Rect partner;

        Axis(int range) {
            this.range = range;
            this.best = range + 0.001;
        }

        void offer(double candidate, Rect from) {
            double distance = Math.abs(candidate);
            if (distance <= range && distance < best) {
                best = distance;
                delta = candidate;
                partner = from;
            }
        }
    }

    /** The partner line closest to any of the moving box's own lines – the one the snap matched. */
    private static float nearestLine(float[] mine, float[] theirs) {
        float best = theirs[0];
        float bestDistance = Float.MAX_VALUE;
        for (float their : theirs) {
            for (float my : mine) {
                float distance = Math.abs(their - my);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = their;
                }
            }
        }
        return best;
    }
}
