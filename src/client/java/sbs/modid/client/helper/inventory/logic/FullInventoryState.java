/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.inventory.logic;

/**
 * Decides <b>when</b> the full-inventory warning fires, given nothing but a free-slot count and a
 * clock.
 *
 * <p>This is the half of the feature that can be wrong in a way the player notices, so it is the
 * half that is pure and unit-tested. {@link FreeSlotWatcher} owns everything that needs a
 * Minecraft client; this class owns the two rules that make the warning bearable:
 *
 * <ul>
 *   <li><b>Recovery re-arms, not time.</b> A warning fires once when the count crosses the
 *       threshold downwards, and then stays quiet however long the player sits full. Getting a
 *       free slot back is the event that means "dealt with it", so that is what re-arms the
 *       warning. A purely timed re-arm would nag someone who has already decided to keep
 *       playing full.</li>
 *   <li><b>A cooldown bounds a genuine second warning.</b> Filling, freeing one slot and filling
 *       again is a real transition each time - mining a vein does it repeatedly - so the
 *       transition alone is not enough. At most one alert per cooldown window.</li>
 * </ul>
 *
 * <p>A suppressed transition is <b>not</b> consumed: the state stays armed, so the warning still
 * arrives as soon as the cooldown expires while the inventory is still low. Dropping it instead
 * would mean the one case the player most wants to hear about - full, and staying full - is the
 * case that goes silent.
 *
 * <p>Not thread-safe; it is only ever touched from the client thread.
 */
public final class FullInventoryState {

    /** Whether a downward crossing is currently allowed to fire. */
    private boolean armed = true;
    /** When the last alert actually went out, in {@code System.currentTimeMillis()} terms. */
    private long lastAlertMs = Long.MIN_VALUE;

    /**
     * Feeds one reading in and says whether to alert.
     *
     * @param freeSlots  empty slots in the main inventory right now
     * @param threshold  warn at or below this many free slots (0 = only when completely full)
     * @param cooldownMs shortest gap between two alerts
     * @param nowMs      current time
     * @return {@code true} exactly on the readings that should raise the alert
     */
    public boolean update(int freeSlots, int threshold, long cooldownMs, long nowMs) {
        if (freeSlots > threshold) {
            // Recovered. Whatever happened before, the next crossing is worth hearing about.
            armed = true;
            return false;
        }
        if (!armed) {
            return false;
        }
        // Long.MIN_VALUE as "never alerted" would overflow this subtraction, so it is tested as
        // the sentinel it is rather than arithmetically.
        if (lastAlertMs != Long.MIN_VALUE && nowMs - lastAlertMs < cooldownMs) {
            return false; // still armed on purpose - see the class doc
        }
        armed = false;
        lastAlertMs = nowMs;
        return true;
    }

    /**
     * Forgets everything. Called on a world change and whenever the feature is switched off, so a
     * warning can never be owed across a server hop - the inventory on the other side is a
     * different reading, not a continuation of this one.
     */
    public void reset() {
        armed = true;
        lastAlertMs = Long.MIN_VALUE;
    }

    /** Whether a crossing would currently be allowed to fire. Exposed for the tests and the log. */
    public boolean armed() {
        return armed;
    }
}
