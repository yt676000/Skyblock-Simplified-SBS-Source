/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.gyro.logic;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The pure part of the Gyro Helper: which item is the wand, the range and radius its lore states,
 * where the vortex centre goes for a given ray result, and the ring's geometry. No Minecraft types,
 * so every rule here is unit-tested without a game.
 *
 * <p><b>The numbers are wiki values.</b> Gravity Storm reaches 24 blocks and pulls within a 10 block
 * radius (Certainty WIKI). The current lore states neither number - it says "at the aimed location" -
 * so the patterns below only matter if a future lore (or a reforge line) starts stating them; until
 * then the configured fallbacks are what is drawn.
 */
public final class GyroAim {

    /** SkyBlock id of the wand. */
    public static final String WAND_ID = "GYROKINETIC_WAND";

    /** Wiki defaults, used whenever the lore states nothing. */
    public static final int DEFAULT_RANGE = 24;
    public static final int DEFAULT_RADIUS = 10;

    /** "within a 10 block radius" / "10-block radius". */
    private static final Pattern LORE_RADIUS =
            Pattern.compile("(?i)(\\d+)[\\s-]*blocks?\\s*radius");
    /** "up to 24 blocks away" / "within 24 blocks". */
    private static final Pattern LORE_RANGE =
            Pattern.compile("(?i)(?:up to|within)\\s+(\\d+)\\s*blocks?+(?!\\s*radius)");
    private static final Pattern COLOUR_CODE = Pattern.compile("§.");

    /** Ring segments: roughly one per 1.25 blocks of circumference, clamped. */
    private static final double SEGMENT_LENGTH = 1.25;
    public static final int MIN_SEGMENTS = 24;
    public static final int MAX_SEGMENTS = 96;

    private GyroAim() {
    }

    public static boolean isWand(String skyblockId) {
        return WAND_ID.equals(skyblockId);
    }

    /** Lore lines joined into one colour-stripped string, so a number wrapped onto the next line still matches. */
    public static String joinLore(List<String> lines) {
        StringBuilder joined = new StringBuilder();
        for (String line : lines) {
            joined.append(COLOUR_CODE.matcher(line).replaceAll("")).append(' ');
        }
        return joined.toString();
    }

    /** The pull radius the lore states, or {@code fallback}. */
    public static int radius(String joinedLore, int fallback) {
        return firstNumber(LORE_RADIUS, joinedLore, fallback);
    }

    /** The cast range the lore states, or {@code fallback}. */
    public static int range(String joinedLore, int fallback) {
        return firstNumber(LORE_RANGE, joinedLore, fallback);
    }

    private static int firstNumber(Pattern pattern, String text, int fallback) {
        if (text != null) {
            Matcher m = pattern.matcher(text);
            if (m.find()) {
                int value = Integer.parseInt(m.group(1));
                if (value > 0 && value <= 100) {
                    return value;
                }
            }
        }
        return Math.max(1, fallback);
    }

    /**
     * Writes the predicted vortex centre into {@code out} and returns {@code true}, or {@code false}
     * when there is no circle to draw.
     *
     * <p>Current rule (until the probe confirms one): a hit on the top face of a block centres on
     * the hit point on that face; a hit on a wall or ceiling centres on the hit point itself; no
     * hit within range draws nothing. The alternatives - the ray's end in mid-air, or the ground
     * below it - are what the probe lines decide between.
     *
     * @param blockHit whether the ray hit a block within range
     * @param topFace  whether the hit face is the block's upward face
     * @param blockY   the hit block's y, used to snap a floor hit exactly onto the face
     */
    public static boolean centre(boolean blockHit, boolean topFace, double hitX, double hitY, double hitZ,
                                 int blockY, double[] out) {
        if (!blockHit) {
            return false;
        }
        out[0] = hitX;
        out[1] = topFace ? blockY + 1.0 : hitY;
        out[2] = hitZ;
        return true;
    }

    /** Segment count for a ring of {@code radius} blocks. */
    public static int segments(double radius) {
        int n = (int) Math.round(2.0 * Math.PI * radius / SEGMENT_LENGTH);
        return Math.max(MIN_SEGMENTS, Math.min(MAX_SEGMENTS, n));
    }

    /**
     * Unit-circle offsets for a ring, built once per radius. {@code dx[i]}, {@code dz[i]} is vertex
     * {@code i} already scaled by the radius; vertex {@code n} wraps to vertex 0.
     */
    public record Ring(double radius, double[] dx, double[] dz) {

        public static Ring of(double radius) {
            int n = segments(radius);
            double[] dx = new double[n];
            double[] dz = new double[n];
            for (int i = 0; i < n; i++) {
                double angle = 2.0 * Math.PI * i / n;
                dx[i] = Math.cos(angle) * radius;
                dz[i] = Math.sin(angle) * radius;
            }
            return new Ring(radius, dx, dz);
        }

        public int size() {
            return dx.length;
        }
    }
}
