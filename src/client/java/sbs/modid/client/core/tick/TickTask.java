/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.tick;

import sbs.modid.client.core.perf.TierScheduler;

import java.util.function.Predicate;

/**
 * One client-tick consumer: what it is called in {@code /sbs perf}, how much the budget may defer it,
 * when it is relevant at all, and the work itself.
 *
 * <p>The gate is the first level - "could this feature do anything here?" - and is asked of the
 * shared {@link TickContext}, never of the sidebar. The feature's own checks (its toggle, its throttle,
 * its room) stay inside {@link #body} as the second level. A gate must be a superset of where the body
 * does anything observable; when in doubt it is {@link #ALWAYS}.
 *
 * @param name     the Perf section name ({@code tick.X})
 * @param tier     {@link TierScheduler.Tier#CRITICAL} unless running less often loses nothing
 * @param gate     relevance on this tick's context; a non-capturing lambda, so it allocates nothing
 * @param body     the work
 * @param measured whether the dispatcher opens the Perf section around the body - {@code false} only
 *                 for a body that measures itself (sections never nest)
 */
public record TickTask(String name, TierScheduler.Tier tier, Predicate<TickContext> gate, Runnable body,
                       boolean measured) {

    /** No gate: the body runs every tick. */
    public static final Predicate<TickContext> ALWAYS = context -> true;

    /** A measured, CRITICAL task. */
    public static TickTask of(String name, Predicate<TickContext> gate, Runnable body) {
        return new TickTask(name, TierScheduler.Tier.CRITICAL, gate, body, true);
    }

    /** A measured task the budget may defer ({@code NORMAL} / {@code BACKGROUND}). */
    public static TickTask of(String name, TierScheduler.Tier tier, Predicate<TickContext> gate, Runnable body) {
        return new TickTask(name, tier, gate, body, true);
    }

    /** A CRITICAL task whose body opens its own Perf section. */
    public static TickTask selfMeasured(String name, Predicate<TickContext> gate, Runnable body) {
        return new TickTask(name, TierScheduler.Tier.CRITICAL, gate, body, false);
    }
}
