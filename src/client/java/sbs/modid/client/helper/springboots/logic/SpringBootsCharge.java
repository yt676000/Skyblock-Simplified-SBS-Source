/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.springboots.logic;

/**
 * How far the Spring Boots' "To the Moon!" jump is charged. Pure: no Minecraft types, driven once
 * per client tick by {@link SpringBootsTracker} and unit-tested on its own.
 *
 * <p>The ability text is CONFIRMED (bundled item lore, id {@code SPRING_BOOTS}): "Charge your jump
 * by sneaking. The longer you sneak, the higher you will jump!". Everything numeric is not:
 *
 * <ul>
 *   <li>{@link #TICKS_PER_STEP} and {@link #MAX_STEPS} are ESTIMATED - nobody has timed a charge.
 *       Until one is timed the level is sneak ticks divided by the step length.</li>
 *   <li>When the player names the charge sound in the config, each heard sound counts as one step
 *       instead ({@link #onStepSound}); the level is then {@link #soundConfirmed()} and the time
 *       estimate is no longer used for that charge.</li>
 * </ul>
 *
 * <p>A charge ends three ways: sneak released while the boots are worn ({@link Event#RELEASED},
 * the jump), boots taken off ({@link Event#RESET}), or {@link #reset()} on a world change.
 */
public final class SpringBootsCharge {

    /** ESTIMATED: ticks of sneaking per charge step. */
    public static final int TICKS_PER_STEP = 5;

    /** ESTIMATED: the number of charge steps at full charge. */
    public static final int MAX_STEPS = 6;

    /** What one {@link #update} call did. */
    public enum Event {
        /** Nothing changed state. */
        NONE,
        /** A charge began this tick. */
        STARTED,
        /** Sneak was released with the boots on: the jump. {@link #lastRelease} holds the charge. */
        RELEASED,
        /** The charge was dropped without a jump (boots taken off). */
        RESET
    }

    /** The charge at the moment sneak was released. */
    public record Release(int ticks, int steps, boolean soundConfirmed) {
    }

    private final int ticksPerStep;
    private final int maxSteps;

    private boolean charging;
    private int ticks;
    private int soundSteps;
    private Release lastRelease;

    public SpringBootsCharge() {
        this(TICKS_PER_STEP, MAX_STEPS);
    }

    public SpringBootsCharge(int ticksPerStep, int maxSteps) {
        if (ticksPerStep <= 0 || maxSteps <= 0) {
            throw new IllegalArgumentException("ticksPerStep and maxSteps must be positive");
        }
        this.ticksPerStep = ticksPerStep;
        this.maxSteps = maxSteps;
    }

    /**
     * Advances one client tick.
     *
     * @param worn     the feet slot holds Spring Boots
     * @param sneaking the player is sneaking
     */
    public Event update(boolean worn, boolean sneaking) {
        if (!worn) {
            if (charging) {
                clear();
                return Event.RESET;
            }
            return Event.NONE;
        }
        if (sneaking) {
            if (!charging) {
                charging = true;
                ticks = 1;
                soundSteps = 0;
                return Event.STARTED;
            }
            ticks++;
            return Event.NONE;
        }
        if (charging) {
            lastRelease = new Release(ticks, level(), soundConfirmed());
            clear();
            return Event.RELEASED;
        }
        return Event.NONE;
    }

    /** One heard charge sound; ignored while no charge is running. */
    public void onStepSound() {
        if (charging && soundSteps < maxSteps) {
            soundSteps++;
        }
    }

    /** Drops any charge without a jump (world change, feature switched off). */
    public void reset() {
        clear();
    }

    private void clear() {
        charging = false;
        ticks = 0;
        soundSteps = 0;
    }

    public boolean charging() {
        return charging;
    }

    /** Ticks sneaked in the current charge; 0 when idle. */
    public int ticks() {
        return ticks;
    }

    /** Whether the current charge is counted from heard sounds rather than estimated from time. */
    public boolean soundConfirmed() {
        return soundSteps > 0;
    }

    /** The current step, 0..{@link #max()}. */
    public int level() {
        if (!charging) {
            return 0;
        }
        if (soundSteps > 0) {
            return soundSteps;
        }
        return Math.min(maxSteps, ticks / ticksPerStep);
    }

    public int max() {
        return maxSteps;
    }

    public boolean full() {
        return charging && level() >= maxSteps;
    }

    /** The charge of the most recent jump, or {@code null} before the first. */
    public Release lastRelease() {
        return lastRelease;
    }
}
