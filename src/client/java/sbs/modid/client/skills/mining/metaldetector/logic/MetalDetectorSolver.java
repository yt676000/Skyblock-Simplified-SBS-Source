/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.metaldetector.logic;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the buried treasure is, from a handful of "I was here, it said this far" readings.
 *
 * <h2>The maths</h2>
 * Each reading is a sphere: the treasure lies somewhere on the shell of radius {@code d} around the
 * point the reading was taken from. Three spheres meet in (generally) one place, and more than three
 * over-determine it, which is what makes the noise in the readings cancel instead of accumulate.
 *
 * <p>Sphere equations are quadratic, but the quadratic term is the same in all of them - so
 * subtracting one chosen sphere from each of the others leaves a <b>linear</b> system in
 * {@code (x, y, z)}:
 *
 * <pre>
 *   2(xi - x0)X + 2(yi - y0)Y + 2(zi - z0)Z = (xi² + yi² + zi² - di²) - (x0² + y0² + z0² - d0²)
 * </pre>
 *
 * That is solved in the least-squares sense through the normal equations {@code AᵀA v = Aᵀb}, a 3x3
 * system small enough that Gaussian elimination with partial pivoting is clearer here than pulling in
 * a matrix library for it.
 *
 * <h2>Why count is not the test</h2>
 * Four readings taken while walking down a corridor lie on a straight line, and a line of spheres
 * does not pin a point - it pins a <i>circle</i> around that line. The solve still returns
 * something, and that something is confidently wrong, which the brief calls out as worse than
 * showing nothing. So {@link #spread} measures how far the sample positions get from their own best-
 * fit line, and a set that never leaves it is refused however many readings it holds.
 *
 * <p>The residual comes back with the answer for the same reason: the caller is expected to draw a
 * radius or say "keep moving", never a bare point the player will trust more than it deserves.
 *
 * <h2>No Minecraft in here on purpose</h2>
 * A real sample needs a player in a world, which does not exist in a unit test - so this takes plain
 * doubles and {@code MetalDetectorSolverTest} drives it with recorded and synthetic sets. The
 * tracker does the game-facing half.
 */
public final class MetalDetectorSolver {

    /** Readings closer together than this add nothing but noise, so the tracker drops them. */
    public static final double MIN_SAMPLE_SPACING = 2.0;

    /**
     * Fewest readings worth solving. Three is the floor for the horizontal position; a full
     * three-dimensional fix needs four that are <b>not all at the same height</b>, which is a
     * condition a player walking one mine floor never meets - see {@link #solve}.
     */
    public static final int MIN_SAMPLES = 3;

    /** Below this, an axis of the normal equations carries no information and cannot be solved. */
    private static final double AXIS_EPSILON = 1e-6;

    /**
     * How far the sample positions must get from their own best-fit line before the geometry is
     * worth solving. Below this they are effectively collinear and the answer is a circle.
     */
    public static final double MIN_SPREAD = 1.5;

    /** One reading: where the player stood, and what the detector said. */
    public record Sample(double x, double y, double z, double distance) {
    }

    /**
     * A solved position with the evidence for it.
     *
     * @param residual root-mean-square disagreement, in blocks, between the fitted point and the
     *                 readings. Small means the readings agree; large means one of them is wrong or
     *                 the target moved (a treasure was dug up mid-set).
     */
    public record Fix(double x, double y, double z, double residual, int samples, boolean planar) {

        /**
         * Whether the height was <b>derived</b> rather than solved: true whenever the readings were
         * all taken at one level, which is what walking a mine floor produces. {@code x} and
         * {@code z} are exact either way; {@code y} is the weaker half of the answer and the caller
         * should say so.
         */
        public boolean heightIsDerived() {
            return planar;
        }
    }

    private MetalDetectorSolver() {
    }

    /**
     * The best-fit treasure position, or {@code null} when the readings cannot pin one.
     *
     * <p>Null means "not enough to say", and it is returned for three different reasons on purpose -
     * too few readings, readings too close to a straight line, and a system with no solution all end
     * the same way, because the caller's answer to all three is the same: ask the player to move and
     * take another reading.
     */
    public static Fix solve(List<Sample> samples) {
        if (samples == null || samples.size() < MIN_SAMPLES) {
            return null;
        }
        List<Sample> used = new ArrayList<>(samples);
        if (spread(used) < MIN_SPREAD) {
            return null;   // a line of readings pins a circle, not a point
        }
        Sample base = used.get(0);
        double baseK = base.x() * base.x() + base.y() * base.y() + base.z() * base.z()
                - base.distance() * base.distance();

        // Normal equations, accumulated directly: AᵀA is 3x3 and symmetric, Aᵀb is 3 long.
        double[][] ata = new double[3][3];
        double[] atb = new double[3];
        for (int i = 1; i < used.size(); i++) {
            Sample s = used.get(i);
            double[] row = {
                    2 * (s.x() - base.x()),
                    2 * (s.y() - base.y()),
                    2 * (s.z() - base.z())};
            double k = s.x() * s.x() + s.y() * s.y() + s.z() * s.z()
                    - s.distance() * s.distance();
            double rhs = k - baseK;
            for (int r = 0; r < 3; r++) {
                for (int c = 0; c < 3; c++) {
                    ata[r][c] += row[r] * row[c];
                }
                atb[r] += row[r] * rhs;
            }
        }
        // A set of readings all taken at one height puts nothing at all in the Y column - the
        // subtraction that linearises the spheres cancels the y terms outright when every yi is the
        // same. That is not an edge case, it is what a player walking a mine floor always produces,
        // so it gets the second path rather than a refusal.
        if (ata[1][1] > AXIS_EPSILON) {
            double[] point = solve3(ata, atb);
            if (point != null) {
                return new Fix(point[0], point[1], point[2], residual(used, point), used.size(),
                        false);
            }
        }
        return solvePlanar(used);
    }

    /**
     * The horizontal fix, plus a height derived from it.
     *
     * <p>When every reading is at one height the y terms cancel and what is left constrains X and Z
     * <b>exactly</b> - the horizontal answer is as good as a full solve, and it is right whether the
     * detector reports a flat distance or a true one. Only the height is left, and one sphere gives
     * it: whatever the reading has left over after the horizontal leg is the vertical leg, and the
     * treasure is buried, so it is below. Readings whose distance is already used up horizontally
     * contribute nothing to the height and are skipped - which is also what a flat distance looks
     * like, and then the height is simply the level the player walked at.
     */
    private static Fix solvePlanar(List<Sample> used) {
        Sample base = used.get(0);
        double baseK = base.x() * base.x() + base.z() * base.z() - base.distance() * base.distance();
        double[][] ata = new double[2][2];
        double[] atb = new double[2];
        for (int i = 1; i < used.size(); i++) {
            Sample s = used.get(i);
            double[] row = {2 * (s.x() - base.x()), 2 * (s.z() - base.z())};
            double rhs = (s.x() * s.x() + s.z() * s.z() - s.distance() * s.distance()) - baseK;
            for (int r = 0; r < 2; r++) {
                for (int c = 0; c < 2; c++) {
                    ata[r][c] += row[r] * row[c];
                }
                atb[r] += row[r] * rhs;
            }
        }
        double det = ata[0][0] * ata[1][1] - ata[0][1] * ata[1][0];
        if (Math.abs(det) < AXIS_EPSILON) {
            return null;
        }
        double x = (atb[0] * ata[1][1] - ata[0][1] * atb[1]) / det;
        double z = (ata[0][0] * atb[1] - atb[0] * ata[1][0]) / det;

        double dropSum = 0;
        int drops = 0;
        double levelSum = 0;
        for (Sample s : used) {
            levelSum += s.y();
            double dx = x - s.x();
            double dz = z - s.z();
            double leftOver = s.distance() * s.distance() - (dx * dx + dz * dz);
            if (leftOver > 0) {
                dropSum += Math.sqrt(leftOver);
                drops++;
            }
        }
        double level = levelSum / used.size();
        double y = drops == 0 ? level : level - dropSum / drops;
        double[] point = {x, y, z};
        return new Fix(x, y, z, residual(used, point), used.size(), true);
    }

    /**
     * How far the sample positions get from their own best-fit line, in blocks.
     *
     * <p>The largest perpendicular distance, not an average: one reading well off the corridor is
     * exactly what rescues an otherwise degenerate set, and averaging would drown it.
     */
    static double spread(List<Sample> samples) {
        if (samples.size() < 3) {
            return 0;
        }
        Sample first = samples.get(0);
        // Direction of the line: from the first reading to the one furthest from it.
        double bestLen = -1;
        double dx = 0;
        double dy = 0;
        double dz = 0;
        for (Sample s : samples) {
            double ax = s.x() - first.x();
            double ay = s.y() - first.y();
            double az = s.z() - first.z();
            double len = ax * ax + ay * ay + az * az;
            if (len > bestLen) {
                bestLen = len;
                dx = ax;
                dy = ay;
                dz = az;
            }
        }
        double axisLen = Math.sqrt(bestLen);
        if (axisLen < 1e-6) {
            return 0;   // every reading taken from the same spot
        }
        dx /= axisLen;
        dy /= axisLen;
        dz /= axisLen;

        double worst = 0;
        for (Sample s : samples) {
            double ax = s.x() - first.x();
            double ay = s.y() - first.y();
            double az = s.z() - first.z();
            double along = ax * dx + ay * dy + az * dz;
            double px = ax - along * dx;
            double py = ay - along * dy;
            double pz = az - along * dz;
            worst = Math.max(worst, Math.sqrt(px * px + py * py + pz * pz));
        }
        return worst;
    }

    /** RMS disagreement in blocks between the fitted point and every reading's sphere. */
    private static double residual(List<Sample> samples, double[] point) {
        double sum = 0;
        for (Sample s : samples) {
            double dx = point[0] - s.x();
            double dy = point[1] - s.y();
            double dz = point[2] - s.z();
            double error = Math.sqrt(dx * dx + dy * dy + dz * dz) - s.distance();
            sum += error * error;
        }
        return Math.sqrt(sum / samples.size());
    }

    /** 3x3 Gaussian elimination with partial pivoting; null when the system is singular. */
    private static double[] solve3(double[][] a, double[] b) {
        double[][] m = {
                {a[0][0], a[0][1], a[0][2], b[0]},
                {a[1][0], a[1][1], a[1][2], b[1]},
                {a[2][0], a[2][1], a[2][2], b[2]}};
        for (int col = 0; col < 3; col++) {
            int pivot = col;
            for (int r = col + 1; r < 3; r++) {
                if (Math.abs(m[r][col]) > Math.abs(m[pivot][col])) {
                    pivot = r;
                }
            }
            if (Math.abs(m[pivot][col]) < 1e-9) {
                return null;   // degenerate: the readings do not constrain all three axes
            }
            double[] swap = m[col];
            m[col] = m[pivot];
            m[pivot] = swap;

            for (int r = 0; r < 3; r++) {
                if (r == col) {
                    continue;
                }
                double factor = m[r][col] / m[col][col];
                for (int c = col; c < 4; c++) {
                    m[r][c] -= factor * m[col][c];
                }
            }
        }
        double[] out = new double[3];
        for (int i = 0; i < 3; i++) {
            out[i] = m[i][3] / m[i][i];
            if (!Double.isFinite(out[i])) {
                return null;
            }
        }
        return out;
    }
}
