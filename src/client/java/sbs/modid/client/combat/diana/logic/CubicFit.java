/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.diana.logic;

/**
 * Least-squares fit of a cubic through a handful of samples, and the two things the spade guess
 * needs from one: its value and its slope at a parameter.
 *
 * <h2>Sampled by index, not by time</h2>
 *
 * <p>The caller hands in the n-th point of a particle trail as the sample at {@code t = n}. Packet
 * timings are not used and must not be: they carry network jitter, they arrive in bursts, and the
 * curve being recovered is a shape the server drew rather than a thing that moved. Index is the
 * parameter the shape was drawn against.
 *
 * <h2>Normal equations, and why that is fine here</h2>
 *
 * <p>Solving {@code (XᵀX)c = Xᵀy} by Gaussian elimination squares the condition number, which is
 * the standard objection to it. It does not bite at this size: four coefficients, a dozen samples,
 * and a parameter that runs 0, 1, 2, 3 rather than over some wide range. The alternative - a QR
 * decomposition - would be more code for an accuracy nobody could measure against a burrow that is
 * a whole block wide.
 *
 * <p>A singular system is reported as {@code null} rather than as a plausible-looking answer full
 * of infinities. Points sitting on top of each other produce exactly that, and a guess derived from
 * one would send the player somewhere with total confidence.
 */
public final class CubicFit {

    /** Degree plus one: a cubic has four coefficients. */
    private static final int TERMS = 4;

    /** Below this a pivot counts as zero and the system is called singular. */
    private static final double EPSILON = 1.0E-12;

    private CubicFit() {
    }

    /**
     * Fits {@code y[i]} against {@code t = i}.
     *
     * @return four coefficients, lowest power first, or {@code null} when the samples do not
     *         determine a cubic
     */
    public static double[] fit(double[] samples) {
        if (samples == null || samples.length < TERMS) {
            return null;
        }
        // Normal equations. normal[r][c] = sum over samples of t^(r+c); rhs[r] = sum of y * t^r.
        double[][] normal = new double[TERMS][TERMS + 1];
        for (int i = 0; i < samples.length; i++) {
            double t = i;
            double[] powers = new double[TERMS];
            powers[0] = 1.0;
            for (int p = 1; p < TERMS; p++) {
                powers[p] = powers[p - 1] * t;
            }
            for (int r = 0; r < TERMS; r++) {
                for (int c = 0; c < TERMS; c++) {
                    normal[r][c] += powers[r] * powers[c];
                }
                normal[r][TERMS] += powers[r] * samples[i];
            }
        }
        return solve(normal);
    }

    /** Gaussian elimination with partial pivoting on an augmented 4x5. {@code null} if singular. */
    private static double[] solve(double[][] m) {
        for (int col = 0; col < TERMS; col++) {
            int pivot = col;
            for (int row = col + 1; row < TERMS; row++) {
                if (Math.abs(m[row][col]) > Math.abs(m[pivot][col])) {
                    pivot = row;
                }
            }
            if (Math.abs(m[pivot][col]) < EPSILON) {
                return null;
            }
            double[] swap = m[col];
            m[col] = m[pivot];
            m[pivot] = swap;

            for (int row = 0; row < TERMS; row++) {
                if (row == col) {
                    continue;
                }
                double factor = m[row][col] / m[col][col];
                for (int c = col; c <= TERMS; c++) {
                    m[row][c] -= factor * m[col][c];
                }
            }
        }
        double[] out = new double[TERMS];
        for (int row = 0; row < TERMS; row++) {
            out[row] = m[row][TERMS] / m[row][row];
            if (!Double.isFinite(out[row])) {
                return null;
            }
        }
        return out;
    }

    /** The polynomial's value at {@code t}, by Horner. */
    public static double valueAt(double[] coefficients, double t) {
        double result = 0.0;
        for (int i = coefficients.length - 1; i >= 0; i--) {
            result = result * t + coefficients[i];
        }
        return result;
    }

    /** The polynomial's slope at {@code t}. */
    public static double slopeAt(double[] coefficients, double t) {
        double result = 0.0;
        for (int i = coefficients.length - 1; i >= 1; i--) {
            result = result * t + coefficients[i] * i;
        }
        return result;
    }
}
