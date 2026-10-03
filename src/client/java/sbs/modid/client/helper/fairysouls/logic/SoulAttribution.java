/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.fairysouls.logic;

import sbs.modid.client.helper.fairysouls.model.FairySoul;

import java.util.List;

/**
 * Which catalogued soul a collection line belongs to. Pure - positions in, soul out - so the rules
 * are unit-tested.
 *
 * <p><b>Why not "exactly one within 6 blocks" any more (2026-09-26).</b> That rule refused the Hub
 * pair hub-2 / hub-3 (2.45 blocks apart, both real) every time, and the wrongly learned heads five
 * blocks away made it worse: the play log shows "4 candidate souls within 6 blocks at (35, 66, -36)
 * ... ambiguous, nothing recorded", so the player clicked souls that never got recorded. The order
 * now is:
 * <ol>
 *   <li><b>What was clicked.</b> The block or entity the player interacted with in the last
 *       {@link #CLICK_WINDOW_MS} ms: the soul nearest THAT, within {@link #CLICK_RADIUS}. Exact,
 *       whatever else is nearby.</li>
 *   <li><b>Clearly nearest.</b> Otherwise the soul nearest the player, if it is within
 *       {@link #NEAREST_MAX} and the runner-up is at least {@link #RUNNER_UP_RATIO} times as far.</li>
 *   <li>Otherwise nothing - still a refusal to guess.</li>
 * </ol>
 * Learned souls are never candidates: they come from the self-learning scanner, which is exactly
 * what put decorations into this list.
 */
public final class SoulAttribution {

    public static final long CLICK_WINDOW_MS = 1_000L;
    public static final double CLICK_RADIUS = 1.5;
    public static final double NEAREST_MAX = 2.0;
    public static final double RUNNER_UP_RATIO = 1.5;

    private SoulAttribution() {
    }

    /** How the soul was picked, for the log line. */
    public enum Via { CLICK, NEAREST }

    /** A pick: the soul, or {@code null} with the reason in {@code why}. */
    public record Result(FairySoul soul, Via via, String why) {
    }

    /**
     * @param souls   the island's souls (learned ones are skipped here)
     * @param player  the player's position {x, y, z}
     * @param clicked positions of the recent interaction target, or {@code null}/empty when none -
     *                several points because an entity's feet and head are both fair "where it is"
     */
    public static Result pick(List<FairySoul> souls, double[] player, List<double[]> clicked) {
        if (clicked != null && !clicked.isEmpty()) {
            FairySoul best = null;
            double bestDistance = Double.MAX_VALUE;
            for (FairySoul soul : souls) {
                if (soul.learned) {
                    continue;
                }
                for (double[] point : clicked) {
                    double d = distance(soul, point);
                    if (d < bestDistance) {
                        bestDistance = d;
                        best = soul;
                    }
                }
            }
            if (best != null && bestDistance <= CLICK_RADIUS) {
                return new Result(best, Via.CLICK, "");
            }
            // A click that was not on a soul says nothing either way - fall through.
        }
        FairySoul nearest = null;
        double first = Double.MAX_VALUE;
        double second = Double.MAX_VALUE;
        for (FairySoul soul : souls) {
            if (soul.learned) {
                continue;
            }
            double d = distance(soul, player);
            if (d < first) {
                second = first;
                first = d;
                nearest = soul;
            } else if (d < second) {
                second = d;
            }
        }
        if (nearest == null) {
            return new Result(null, null, "no catalogued soul on this island");
        }
        if (first > NEAREST_MAX) {
            return new Result(null, null, String.format(java.util.Locale.ROOT,
                    "nearest soul %.1f blocks away (max %.1f)", first, NEAREST_MAX));
        }
        if (second < first * RUNNER_UP_RATIO) {
            return new Result(null, null, String.format(java.util.Locale.ROOT,
                    "ambiguous: nearest %.1f, next %.1f blocks", first, second));
        }
        return new Result(nearest, Via.NEAREST, "");
    }

    /** From a point to the soul's block centre. */
    static double distance(FairySoul soul, double[] p) {
        double dx = soul.x + 0.5 - p[0];
        double dy = soul.y + 0.5 - p[1];
        double dz = soul.z + 0.5 - p[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
