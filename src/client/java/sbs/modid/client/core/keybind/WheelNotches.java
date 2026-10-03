/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.keybind;

/**
 * Turns a stream of wheel deltas into whole notches, so a wheel keybind fires <b>once</b> per turn
 * of the wheel.
 *
 * <p>A notched mouse reports one {@code ±1.0} per click and needs none of this. A precision wheel or
 * a trackpad reports a stream of fractions for the same gesture, and firing on each of them would
 * run a keybind's command five or ten times for one flick — which is exactly the "one player action,
 * at most one command" rule the mod is built on. Accumulating to a notch keeps both kinds of
 * hardware at one fire per turn.
 *
 * <p>A change of direction resets the travel: a flick back the other way must not be delayed by
 * whatever was left over from the flick before it.
 *
 * <p>One instance per consumer, never a shared static: the in-world hotkeys and the container
 * hotkeys see the same physical wheel but are asked at different points in the same event, and a
 * single counter would be advanced twice by one notch.
 *
 * <p>Client thread only (both mouse callbacks are), so nothing here is synchronized.
 */
public final class WheelNotches {

    /** Wheel travel seen so far that is not yet worth a notch. */
    private double travel;

    /**
     * Feeds one wheel event.
     *
     * @return {@link Keys#WHEEL_UP} / {@link Keys#WHEEL_DOWN} when this event completes a notch,
     *         {@code 0} while the wheel has not turned far enough
     */
    public int step(double yOffset) {
        if (yOffset == 0) {
            return 0;
        }
        if (travel != 0 && Math.signum(yOffset) != Math.signum(travel)) {
            travel = 0;
        }
        travel += yOffset;
        if (Math.abs(travel) < 1.0) {
            return 0;
        }
        travel = 0;
        return Keys.ofWheel(yOffset);
    }
}
