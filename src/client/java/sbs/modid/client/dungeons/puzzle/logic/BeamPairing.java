/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.puzzle.logic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Creeper Beams geometry, free of Minecraft: which lit lanterns belong together.
 *
 * <p>The room's rule (wiki, unverified in 26.2): two lanterns are a pair when the
 * straight beam between them runs through the creeper in the middle of the room. So for every two
 * lanterns this measures how close the segment between them passes to the creeper's centre, keeps
 * the ones that pass close and on opposite sides of it, and matches greedily from the closest miss.
 *
 * <p><b>A pair is only reported when it is unambiguous.</b> If either lantern has a second candidate
 * partner whose miss is within {@link #AMBIGUITY_MARGIN} of the chosen one, the pair is withheld and
 * counted as ambiguous - connecting the wrong two lanterns wastes the pair, so "not sure" draws
 * nothing, as everywhere in the puzzle framework.
 *
 * <p>No coordinate table: the lantern positions come from the world, the centre from the creeper
 * entity, and nothing here knows where in the room either sits.
 */
public final class BeamPairing {

    /**
     * Largest distance, in blocks, between the beam and the creeper's centre for a pair to count.
     * {@code ESTIMATED}: the creeper is about one block wide and a beam the game accepts passes
     * through its body, not necessarily its exact centre; 2.0 leaves room for that and for the
     * difference between the entity's box centre and wherever the game aims. Tighten from the logged
     * {@code miss=} values once a run has produced them.
     */
    public static final double MAX_MISS = 2.0;

    /** Second-best partner within this many blocks of the best one makes the pair ambiguous. */
    public static final double AMBIGUITY_MARGIN = 0.75;

    /**
     * Where along the segment the closest point to the centre may lie, as a fraction. Keeps two
     * lanterns on the same side of the creeper (whose extended line merely points at it) out.
     */
    private static final double MIN_T = 0.1;
    private static final double MAX_T = 0.9;

    /** A lantern or the creeper centre, in world (or any consistent) block coordinates. */
    public record Point(double x, double y, double z) {
    }

    /** Two lantern indices into the input list, {@code a < b}, and how far the beam misses the centre. */
    public record Pair(int a, int b, double miss) {
    }

    /**
     * @param pairs      the certain pairs, closest miss first
     * @param ambiguous  how many lanterns had a candidate partner but no certain one
     * @param unpaired   lantern indices with no candidate partner at all
     */
    public record Result(List<Pair> pairs, int ambiguous, List<Integer> unpaired) {
        public static final Result EMPTY = new Result(List.of(), 0, List.of());
    }

    private BeamPairing() {
    }

    /**
     * Pairs the lanterns around {@code centre}. Lanterns already used (turned to prismarine) should
     * not be passed in; this only sees the ones still to connect.
     */
    public static Result solve(List<Point> lanterns, Point centre) {
        int n = lanterns.size();
        if (n < 2 || centre == null) {
            return n == 0 ? Result.EMPTY : new Result(List.of(), 0, indices(n));
        }
        double[][] miss = new double[n][n];
        List<Pair> candidates = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Arrays.fill(miss[i], Double.NaN);
            for (int j = 0; j < i; j++) {
                double d = segmentMiss(lanterns.get(j), lanterns.get(i), centre);
                miss[i][j] = d;
                miss[j][i] = d;
                if (!Double.isNaN(d) && d <= MAX_MISS) {
                    candidates.add(new Pair(j, i, d));
                }
            }
        }
        candidates.sort((p, q) -> Double.compare(p.miss(), q.miss()));

        boolean[] used = new boolean[n];
        boolean[] hasCandidate = new boolean[n];
        for (Pair candidate : candidates) {
            hasCandidate[candidate.a()] = true;
            hasCandidate[candidate.b()] = true;
        }
        List<Pair> pairs = new ArrayList<>();
        boolean[] ambiguousLantern = new boolean[n];
        for (Pair candidate : candidates) {
            if (used[candidate.a()] || used[candidate.b()]) {
                continue;
            }
            boolean rivalled = rivalled(miss, used, candidate.a(), candidate.b(), candidate.miss())
                    || rivalled(miss, used, candidate.b(), candidate.a(), candidate.miss());
            used[candidate.a()] = true;
            used[candidate.b()] = true;
            if (rivalled) {
                ambiguousLantern[candidate.a()] = true;
                ambiguousLantern[candidate.b()] = true;
            } else {
                pairs.add(candidate);
            }
        }

        int ambiguous = 0;
        List<Integer> unpaired = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (ambiguousLantern[i] || (hasCandidate[i] && !used[i])) {
                ambiguous++;
            } else if (!hasCandidate[i]) {
                unpaired.add(i);
            }
        }
        return new Result(List.copyOf(pairs), ambiguous, List.copyOf(unpaired));
    }

    /** Whether {@code self} has a still-free partner other than {@code partner} nearly as good as {@code best}. */
    private static boolean rivalled(double[][] miss, boolean[] used, int self, int partner, double best) {
        double[] row = miss[self];
        for (int other = 0; other < row.length; other++) {
            if (other == self || other == partner || used[other] || Double.isNaN(row[other])) {
                continue;
            }
            if (row[other] <= MAX_MISS && row[other] - best < AMBIGUITY_MARGIN) {
                return true;
            }
        }
        return false;
    }

    /**
     * Distance from {@code c} to the segment {@code p}-{@code q}, or {@code NaN} when the closest
     * point is not between the two ends (the centre is not between the lanterns).
     */
    static double segmentMiss(Point p, Point q, Point c) {
        double dx = q.x() - p.x();
        double dy = q.y() - p.y();
        double dz = q.z() - p.z();
        double len2 = dx * dx + dy * dy + dz * dz;
        if (len2 < 1e-9) {
            return Double.NaN;
        }
        double t = ((c.x() - p.x()) * dx + (c.y() - p.y()) * dy + (c.z() - p.z()) * dz) / len2;
        if (t < MIN_T || t > MAX_T) {
            return Double.NaN;
        }
        double ex = p.x() + t * dx - c.x();
        double ey = p.y() + t * dy - c.y();
        double ez = p.z() + t * dz - c.z();
        return Math.sqrt(ex * ex + ey * ey + ez * ez);
    }

    private static List<Integer> indices(int n) {
        List<Integer> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(i);
        }
        return out;
    }

    /**
     * Every pair of lanterns whose centre-to-centre line passes through the box {@code min..max} -
     * the creeper's hitbox, grown by its bobbing. This is the room's rule read literally ("the beam
     * goes through the creeper"), and it is what the maintainer asked to see (2026-10-16): all the
     * lanterns that match, nothing else. A lantern matching two partners yields both pairs; the
     * strict one-to-one {@link #solve} found 3 pairs among 21 lanterns in that room, this the two
     * the player solved first plus the rest. {@code miss} is 0.
     */
    public static List<Pair> throughBox(List<Point> lanterns, Point min, Point max) {
        List<Pair> out = new ArrayList<>();
        for (int i = 0; i < lanterns.size(); i++) {
            for (int j = i + 1; j < lanterns.size(); j++) {
                if (segmentHitsBox(lanterns.get(i), lanterns.get(j), min, max)) {
                    out.add(new Pair(i, j, 0));
                }
            }
        }
        return out;
    }

    /** Slab test: whether the segment {@code a..b} meets the axis-aligned box {@code min..max}. */
    static boolean segmentHitsBox(Point a, Point b, Point min, Point max) {
        double[] from = {a.x(), a.y(), a.z()};
        double[] d = {b.x() - a.x(), b.y() - a.y(), b.z() - a.z()};
        double[] lo = {min.x(), min.y(), min.z()};
        double[] hi = {max.x(), max.y(), max.z()};
        double t0 = 0;
        double t1 = 1;
        for (int k = 0; k < 3; k++) {
            if (Math.abs(d[k]) < 1e-9) {
                if (from[k] < lo[k] || from[k] > hi[k]) {
                    return false;
                }
                continue;
            }
            double u = (lo[k] - from[k]) / d[k];
            double v = (hi[k] - from[k]) / d[k];
            if (u > v) {
                double s = u;
                u = v;
                v = s;
            }
            t0 = Math.max(t0, u);
            t1 = Math.min(t1, v);
            if (t0 > t1) {
                return false;
            }
        }
        return true;
    }
}
