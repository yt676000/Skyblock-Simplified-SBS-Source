/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.run.model;

/**
 * Pure, Minecraft-free calculation of the Catacombs run score (Skill + Exploration + Speed + Bonus,
 * S = 270, S+ = 300). Kept dependency-free so it is unit-testable once a test source set exists.
 *
 * <p><b>Calibrated 2026-07-23 from a real F7 run</b> (screenshot: rooms 67 %, secrets 75 %, 0 deaths,
 * 18m53s → score 219 / grade B) plus the user's rules:
 * <ul>
 *   <li><b>Speed</b> stays 100 until {@value #SPEED_FULL_SECONDS} s (14 min), then loses
 *       {@value #SPEED_LOSS_PER_MINUTE} per minute. The 14-min breakpoint is exact (user); the
 *       per-minute rate is inferred from the one data point and may still need a nudge.</li>
 *   <li><b>Death penalty</b> is −2 per death, except with a Spirit pet the first death is only −1
 *       (subsequent deaths are still −2).</li>
 * </ul>
 * The game now gives the two completion <b>percentages</b> directly (Cleared %, Secrets Found %), so
 * Skill/Exploration are exact inputs; only Speed is approximate. Unknown inputs ({@code -1}) degrade
 * gracefully and set {@link Result#estimated()}.
 */
public final class DungeonScore {

    private static final double EXPLORE_ROOM_WEIGHT = 60;
    private static final double EXPLORE_SECRET_WEIGHT = 40;
    private static final double SKILL_BASE = 20;
    private static final double SKILL_ROOM_WEIGHT = 80;

    /** Speed is full until this many seconds, then decays (user: 14 min on F7). */
    private static final int SPEED_FULL_SECONDS = 14 * 60;
    /** Points lost per minute past the full-speed window (inferred from one run — tune if needed). */
    private static final double SPEED_LOSS_PER_MINUTE = 5;

    private static final int BONUS_CRYPT_CAP = 5;
    private static final int BONUS_MIMIC = 2;
    /** The Prince's shard bonus: one point, for the first one killed in the run. */
    private static final int BONUS_PRINCE = 1;

    public static final int SCORE_S = 270;
    public static final int SCORE_S_PLUS = 300;

    /**
     * Inputs. {@code roomClearPct} / {@code secretPct} are 0..100 (the game's own Cleared % / Secrets
     * Found %), or {@code -1} when unknown. {@code spiritPet} switches the first-death penalty to −1.
     */
    public record Inputs(int roomClearPct, int secretPct, int deaths, int crypts,
                         int elapsedSeconds, boolean spiritPet, boolean mimicKilled,
                         boolean princeKilled) {
    }

    /** The computed breakdown. {@code estimated} = an input was unknown or the speed rate was applied. */
    public record Result(int skill, int explore, int speed, int bonus, int total, boolean estimated) {
        public boolean isSPlus() {
            return total >= SCORE_S_PLUS;
        }

        public boolean isS() {
            return total >= SCORE_S;
        }
    }

    private DungeonScore() {
    }

    public static Result compute(Inputs in) {
        boolean estimated = false;

        double roomFrac;
        if (in.roomClearPct() >= 0) {
            roomFrac = clamp01(in.roomClearPct() / 100.0);
        } else {
            roomFrac = 0;
            estimated = true;
        }
        double secretFrac;
        if (in.secretPct() >= 0) {
            secretFrac = clamp01(in.secretPct() / 100.0);
        } else {
            secretFrac = 0;
            estimated = true;
        }

        int deaths = Math.max(0, in.deaths());
        double deathPenalty = deaths == 0 ? 0
                : (in.spiritPet() ? 2.0 * deaths - 1 : 2.0 * deaths);
        int skill = (int) Math.round(clamp(SKILL_BASE + SKILL_ROOM_WEIGHT * roomFrac - deathPenalty, 0, 100));

        int explore = Math.min(100,
                (int) Math.floor(EXPLORE_ROOM_WEIGHT * roomFrac)
                        + (int) Math.floor(EXPLORE_SECRET_WEIGHT * secretFrac));

        int speed;
        if (in.elapsedSeconds() > 0) {
            speed = speedScore(in.elapsedSeconds());
            estimated = true; // the per-minute rate is the one still-approximate part
        } else {
            speed = 100;
            estimated = true;
        }

        int bonus = Math.min(BONUS_CRYPT_CAP, Math.max(0, in.crypts()))
                + (in.mimicKilled() ? BONUS_MIMIC : 0)
                + (in.princeKilled() ? BONUS_PRINCE : 0);

        int total = skill + explore + speed + bonus;
        return new Result(skill, explore, speed, bonus, total, estimated);
    }

    private static int speedScore(int elapsedSeconds) {
        if (elapsedSeconds <= SPEED_FULL_SECONDS) {
            return 100;
        }
        double overMinutes = (elapsedSeconds - SPEED_FULL_SECONDS) / 60.0;
        return (int) Math.round(clamp(100 - overMinutes * SPEED_LOSS_PER_MINUTE, 0, 100));
    }

    private static double clamp01(double v) {
        return clamp(v, 0, 1);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
