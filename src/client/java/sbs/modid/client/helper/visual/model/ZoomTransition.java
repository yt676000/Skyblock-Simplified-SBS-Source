/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.visual.model;

/**
 * The <b>movement</b> of a zoom: how far along the way in (or back out) the view currently is.
 *
 * <p>Without this a zoom is a switch – the field of view is replaced wholesale on the frame the key
 * goes down and restored on the frame it comes up, which reads as a teleport rather than as a lens
 * moving. {@link #advance} instead walks a 0..1 blend towards its target every frame and eases it,
 * so the same configured strength arrives smoothly and leaves the same way.
 *
 * <p><b>Time-based, not frame-based.</b> The step is measured off the wall clock, so the zoom takes
 * the same fraction of a second at 30 fps and at 240 fps. A frame gap longer than
 * {@link #MAX_STEP_MS} (alt-tab, a world load, the first frame after the game was paused) is
 * clamped: the zoom carries on from where it was instead of snapping across the whole range.
 *
 * <p>One instance per zoom, like {@link ZoomStrength} – each one is somewhere different.
 */
public final class ZoomTransition {

    /** Bounds of the configurable speed, in percent of the default rate. */
    public static final int MIN_SPEED = 10;
    public static final int MAX_SPEED = 400;

    /** How long a full zoom in / out takes at 100 % speed. */
    private static final float BASE_DURATION_MS = 200f;

    /** Longest frame gap that still counts as one step (see the class note). */
    private static final long MAX_STEP_MS = 100;

    /** 0 = fully zoomed out (normal view), 1 = fully at the configured strength. */
    private float progress;

    /** Wall clock of the previous {@link #advance}; 0 = no previous frame. */
    private long lastMs;

    /**
     * Moves the blend one frame towards {@code zoomedIn} and returns it, eased.
     *
     * @param zoomedIn     whether the zoom is being held right now
     * @param speedPercent {@link #MIN_SPEED}–{@link #MAX_SPEED}; higher is faster
     * @return {@code 0} = render the normal field of view, {@code 1} = the full zoom
     */
    public float advance(boolean zoomedIn, int speedPercent) {
        long now = System.currentTimeMillis();
        long elapsed = lastMs == 0 ? 0 : Math.min(now - lastMs, MAX_STEP_MS);
        lastMs = now;

        float duration = BASE_DURATION_MS * 100f / clampSpeed(speedPercent);
        float step = elapsed / duration;
        progress = zoomedIn ? Math.min(1f, progress + step) : Math.max(0f, progress - step);
        return ease(progress);
    }

    /** Whether the view is all the way back out – the point at which zoom state may be dropped. */
    public boolean idle() {
        return progress <= 0f;
    }

    /** Jumps straight to either end, for when the animation is switched off (or the zoom ends). */
    public void snap(boolean zoomedIn) {
        progress = zoomedIn ? 1f : 0f;
        lastMs = 0;
    }

    /** Smoothstep: the zoom starts and stops gently instead of running at a constant rate. */
    private static float ease(float t) {
        return t * t * (3f - 2f * t);
    }

    public static int clampSpeed(int speedPercent) {
        return Math.clamp(speedPercent, MIN_SPEED, MAX_SPEED);
    }
}
