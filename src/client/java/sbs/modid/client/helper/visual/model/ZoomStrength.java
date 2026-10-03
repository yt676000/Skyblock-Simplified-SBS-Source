/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.visual.model;

/**
 * How far a zoom is zoomed in, and the mouse-wheel fine-tuning on top of it.
 *
 * <p><b>Strength, not field of view.</b> The number the player sets reads the way a zoom is expected
 * to: <b>higher is more zoomed in</b>. Internally a zoom is a field-of-view multiplier, where <i>lower
 * is closer</i> – the exact opposite – so {@link #fovFactor} does that inversion once, here, instead of
 * every setting screen having to explain that a small number means a big zoom.
 *
 * <p><b>The wheel is a temporary nudge.</b> Scrolling while zoomed adjusts the strength for the
 * duration of that hold only, and {@link #reset} drops it the moment the zoom ends. Writing it back
 * into the config instead would mean the settings slider silently moved on its own every time you
 * fine-tuned a shot.
 *
 * <p>One instance per zoom (plain hold-to-zoom, Ether Warp), because each holds its own live nudge.
 */
public final class ZoomStrength {

    /** Bounds of the configurable strength, in percent. 10 = barely closer, 90 = far in. */
    public static final int MIN = 10;
    public static final int MAX = 90;

    /** Strength percentage points one wheel notch adds or removes. */
    private static final int SCROLL_STEP = 5;

    /** Strength dialled in with the wheel during the current hold; cleared when it ends. */
    private int nudge;

    /** The configured strength plus the live wheel nudge, clamped into range. */
    public int effective(int configured) {
        return clamp(clamp(configured) + nudge);
    }

    /** The field-of-view multiplier for a strength: 90 % strength narrows the view to 10 %. */
    public float fovFactor(int configured) {
        return (100 - effective(configured)) / 100.0f;
    }

    /**
     * Applies a wheel notch. Returns whether it changed anything, so the caller can leave the wheel
     * to its normal job (switching hotbar slots) once the zoom is at its limit.
     */
    public boolean scroll(int configured, double scrollY) {
        if (scrollY == 0) {
            return false;
        }
        int before = effective(configured);
        nudge = clamp(before + (int) Math.signum(scrollY) * SCROLL_STEP) - clamp(configured);
        return effective(configured) != before;
    }

    /** Forgets the wheel nudge – called every frame the zoom is not running. */
    public void reset() {
        nudge = 0;
    }

    public static int clamp(int strength) {
        return Math.clamp(strength, MIN, MAX);
    }
}
