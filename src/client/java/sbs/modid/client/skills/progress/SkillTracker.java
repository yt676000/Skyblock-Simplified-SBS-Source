/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.progress;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Live state of whichever skill is currently earning XP – the model behind the Skill Progress
 * overlay, and the one place any future skill statistic should be derived from.
 *
 * <p><b>Detecting "the relevant skill" needs no guessing.</b> Hypixel names the skill in the action
 * bar every time it grants XP ({@code "+40 Mining (312,540/1,000,000)"}), so chopping wood reports
 * Foraging, mining stone or ores reports Mining, and so on. The active skill is simply the one that
 * last paid out; switching activity switches the overlay on its own.
 *
 * <p><b>Repeat-safe.</b> Hypixel re-sends the same action bar many times per second, so counting
 * every parse would inflate the rate wildly. A gain is only recorded when the XP counter actually
 * <i>moves</i>, which makes the accounting exact regardless of how often the line is repeated.
 *
 * <p>Fields are {@code volatile} (written from the action-bar/network thread, read while rendering);
 * the sample history is guarded by its own lock.
 */
public final class SkillTracker {

    // NOTE: constants must be declared before INSTANCE - a static singleton initialised above them
    // would run first and read them as 0/null (a gotcha this codebase has hit before).

    /** How long after the last gain the skill still counts as "actively progressing". */
    private static final long ACTIVE_WINDOW_MS = 8_000L;

    /** Rolling window the XP rate is averaged over. */
    private static final long RATE_WINDOW_MS = 5 * 60_000L;

    /** Hard cap on retained samples, so an endless session cannot grow the deque without bound. */
    private static final int MAX_SAMPLES = 4_096;

    private static final SkillTracker INSTANCE = new SkillTracker();

    /** One recorded XP gain. */
    private record Sample(long time, double amount) {
    }

    private final Deque<Sample> samples = new ArrayDeque<>();

    private volatile String skill;
    private volatile double current;
    private volatile double required;
    private volatile long lastGain;

    /**
     * The last skill to pay out anything at all, and when - recorded even when the action bar
     * carries no {@code (current/required)} part.
     *
     * <p>Kept beside the progress fields rather than folded into them because Hypixel <b>omits</b>
     * the progress once a skill is maxed. Everything above therefore goes quiet for exactly the
     * players who have maxed the skill, and a caller asking the far simpler question - "is this
     * skill earning right now" - would go blind with it. {@link #activeSkillName()} answers that
     * from this pair, at any level.
     */
    private volatile String anySkill;
    private volatile long anyGain;

    private SkillTracker() {
    }

    public static SkillTracker getInstance() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    /**
     * Records that a skill paid out, without needing the progress part.
     *
     * <p>Called for <b>every</b> action-bar payout, including the maxed-skill form Hypixel writes
     * without a {@code (current/required)} tail. Only {@link #activeSkillName()} is built on this;
     * the rate, the ETA and the overlay all still need the progress and go through
     * {@link #onSkillXp}.
     */
    public void onSkillPayout(String skill, double amount) {
        if (skill == null || amount <= 0) {
            return;
        }
        anySkill = skill;
        anyGain = System.currentTimeMillis();
    }

    /**
     * Records an action-bar skill XP gain.
     *
     * @param skill    the skill Hypixel named (e.g. "Mining")
     * @param amount   the XP granted by this action (the "+40")
     * @param current  progress into the current level
     * @param required XP the current level requires
     */
    public void onSkillXp(String skill, double amount, double current, double required) {
        if (skill == null || required <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (samples) {
            if (!skill.equals(this.skill)) {
                // A different skill: drop the history, or the previous activity's rate would
                // poison this one's ETA for the next few minutes.
                samples.clear();
                this.skill = skill;
                this.current = current;
                this.required = required;
                this.lastGain = now;
                return;
            }
            // Only count a gain when the counter really moved. Hypixel repeats the same action bar
            // continuously; without this the rate would climb with the frame rate.
            boolean moved = current != this.current;
            this.current = current;
            this.required = required;
            if (!moved) {
                return;
            }
            this.lastGain = now;
            samples.addLast(new Sample(now, amount));
            trim(now);
        }
    }

    /** Drops samples outside the rate window (and enforces the hard cap). Caller holds the lock. */
    private void trim(long now) {
        while (!samples.isEmpty()
                && (now - samples.peekFirst().time() > RATE_WINDOW_MS || samples.size() > MAX_SAMPLES)) {
            samples.removeFirst();
        }
    }

    /** Forgets everything (e.g. on disconnect), so a new session starts with a clean rate. */
    public void reset() {
        synchronized (samples) {
            samples.clear();
            skill = null;
            current = 0;
            required = 0;
            lastGain = 0;
            anySkill = null;
            anyGain = 0;
        }
    }

    // ------------------------------------------------------------------
    // Derived state
    // ------------------------------------------------------------------

    /** Whether a skill is actively progressing right now – the overlay's visibility condition. */
    public boolean active() {
        return skill != null && required > 0
                && System.currentTimeMillis() - lastGain < ACTIVE_WINDOW_MS;
    }

    public String skill() {
        return skill;
    }

    /**
     * Whichever skill last paid out inside the active window, or {@code null} - the maxed-safe
     * counterpart to {@link #skill()}, which needs the progress part and so goes quiet at max level.
     */
    public String activeSkillName() {
        return System.currentTimeMillis() - anyGain < ACTIVE_WINDOW_MS ? anySkill : null;
    }

    /** Whether Foraging XP is flowing right now - "the player is chopping", at any skill level. */
    public boolean foragingActive() {
        return "Foraging".equalsIgnoreCase(activeSkillName());
    }

    /**
     * When {@code skill} last paid out anything, or {@code 0} - the raw timestamp behind
     * {@link #activeSkillName()}, for callers that need a window other than this class's own.
     */
    public long payoutAt(String skill) {
        return skill != null && skill.equalsIgnoreCase(anySkill) ? anyGain : 0L;
    }

    public double currentXp() {
        return current;
    }

    public double requiredXp() {
        return required;
    }

    /** XP still needed for the next level (never negative). */
    public double remainingXp() {
        return Math.max(0, required - current);
    }

    /** Progress into the current level in {@code [0,1]}. */
    public double progress() {
        return required <= 0 ? 0 : Math.min(1.0, current / required);
    }

    /** The current level, or {@link SkillXpTable#UNKNOWN_LEVEL} if the requirement is unknown. */
    public int level() {
        return SkillXpTable.levelFor(required);
    }

    /** Averaged XP per hour over the rate window, or 0 without enough history to be meaningful. */
    public double xpPerHour() {
        double perSecond = xpPerSecond();
        return perSecond <= 0 ? 0 : perSecond * 3600.0;
    }

    /**
     * Averaged XP per second. Measured across the samples' own span rather than the full window, so
     * a rate appears within seconds of starting instead of ramping up over five minutes.
     */
    private double xpPerSecond() {
        synchronized (samples) {
            if (samples.size() < 2) {
                return 0;
            }
            long span = samples.peekLast().time() - samples.peekFirst().time();
            if (span <= 0) {
                return 0;
            }
            double total = 0;
            for (Sample sample : samples) {
                total += sample.amount();
            }
            return total / (span / 1000.0);
        }
    }

    /** Seconds until the next level at the current rate, or {@code -1} when not predictable. */
    public double etaSeconds() {
        double perSecond = xpPerSecond();
        if (perSecond <= 0) {
            return -1;
        }
        return remainingXp() / perSecond;
    }

    /**
     * How many more actions the next level needs, from the average XP an action has paid so far, or
     * {@code -1} when it cannot be estimated. Only meaningful while the payout per action is
     * consistent, which is exactly the case for the repetitive jobs this overlay is for.
     */
    public int actionsRemaining() {
        double average;
        synchronized (samples) {
            if (samples.isEmpty()) {
                return -1;
            }
            double total = 0;
            for (Sample sample : samples) {
                total += sample.amount();
            }
            average = total / samples.size();
        }
        if (average <= 0) {
            return -1;
        }
        return (int) Math.ceil(remainingXp() / average);
    }
}
