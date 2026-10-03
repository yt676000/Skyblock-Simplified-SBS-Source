/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.terrain;

import net.minecraft.client.Minecraft;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;

/**
 * Holds a framerate target by giving back far terrain until the target is met.
 *
 * <p><b>Reading the framerate is free; changing the distance is not.</b> That single fact shapes
 * everything here. {@code Minecraft.getFps()} is a counter vanilla already keeps, so sampling costs
 * nothing - but <i>raising</i> the drawn distance pulls sections that have never been meshed into
 * the visible set (an uncompiled section is see-through to the occlusion graph, so the graph walks
 * straight into them) and every one of them is queued for a mesh build. That build wave is itself a
 * framerate drop. A controller that nudged the distance every second would therefore spend its life
 * measuring the cost of its own last adjustment, conclude things are still too slow, and oscillate -
 * burning more frames than it ever gave back.
 *
 * <p><b>So it is built to sit still.</b> While the target is being met this does nothing at all: no
 * allocation, no work beyond one integer comparison a second. It only acts on a miss that survives
 * two consecutive seconds, it drops quickly but climbs back slowly and rarely, and after every
 * change it stops measuring entirely until the wave it caused has passed. In practice that is a
 * handful of adjustments when you arrive somewhere heavy, then silence.
 *
 * <p><b>It also checks that it is holding a lever that is connected.</b> A framerate target far
 * above what the machine can do for reasons that have nothing to do with terrain - a GPU limit, a
 * particle storm, a shader - would otherwise have it ratchet all the way to the floor, costing the
 * player every chunk of far terrain and buying nothing. So after each cut it compares the framerate
 * against the one before: if giving up that distance did not measurably help, the cut is <b>put
 * back</b> and the controller stands down for a while. An impossible target settles at "tried it,
 * it was not the terrain" rather than at zero.
 *
 * <p>The floor under all of it is the server's own chunk radius, so the worst this can ever do is
 * leave you with exactly the view you would have had without the module.
 */
public final class FarTerrainAutoDistance {

    /** The slider's ends. 20 is "make it playable at any cost", 360 a high-refresh target. */
    public static final int MIN_TARGET = 20;
    public static final int MAX_TARGET = 360;

    /** One measurement a second - vanilla's own counter updates at exactly that rate. */
    private static final int SAMPLE_TICKS = 20;

    /**
     * How long after an adjustment measuring stops. The mesh wave a raise causes lands inside this
     * window, and attributing it to the new distance is precisely the mistake that makes a naive
     * controller oscillate.
     */
    private static final int SETTLE_TICKS = 60;

    /** A miss has to survive this many consecutive samples before anything moves. */
    private static final int MISSES_BEFORE_CUT = 2;

    /** Below this fraction of the target counts as missing it. */
    private static final double MISS_RATIO = 0.90;

    /** Above this fraction there is enough headroom to try giving distance back. */
    private static final double HEADROOM_RATIO = 1.25;

    /** Climbing back is deliberately rare - each attempt costs a mesh wave. */
    private static final int RAISE_INTERVAL_TICKS = 400;   // 20 s
    private static final int RAISE_STEP = 2;

    /** The biggest single cut, in chunks. Proportional below this, so a bad miss is fixed fast. */
    private static final int MAX_CUT = 8;

    /** A cut has to buy at least this much framerate to be considered worth keeping. */
    private static final double CUT_MUST_GAIN = 0.03;

    /** How long a "lowering does not help here" verdict stands before it is retried. */
    private static final int GIVE_UP_TICKS = 1200;   // 60 s

    /** How long a learned "this much was too far" ceiling stands. */
    private static final int CEILING_TTL_TICKS = 1200;

    private static final int MIN_CHUNKS = 4;

    private static int tickCounter;
    private static int nextSample;
    private static int settleUntil;
    private static int misses;

    /** The clamp in force, in chunks; {@code 0} while the controller is not holding one. */
    private static int applied;

    /** Highest distance known to have missed the target here, and when that was learned. */
    private static int ceiling;
    private static int ceilingAt;

    private static int lastRaise;

    /** Set while a cut is waiting to be judged: what it was before, and the framerate then. */
    private static int pendingRevertTo;
    private static int fpsBeforeCut;

    /** Until this tick the controller has concluded the distance is not what is costing frames. */
    private static int giveUpUntil;

    private FarTerrainAutoDistance() {
    }

    private static SBSConfig.FarTerrainSettings cfg() {
        return ConfigManager.getInstance().get().farTerrain;
    }

    /**
     * The clamp to apply right now, or {@code null} when the controller is not taking anything
     * away. Sitting at the player's own setting is not a clamp - reporting it as one would have
     * every status line claim the framerate is costing you view when it is not.
     */
    public static Integer viewChunks() {
        if (applied <= 0) {
            return null;
        }
        int slider = Minecraft.getInstance().options.renderDistance().get();
        return applied >= slider ? null : applied;
    }

    /** Whether the controller has had to give distance up, for the settings status line. */
    public static boolean engaged() {
        return viewChunks() != null;
    }

    /** True once the controller decided the framerate is not the terrain's fault. */
    public static boolean standingDown() {
        return giveUpUntil > tickCounter;
    }

    public static void reset() {
        applied = 0;
        misses = 0;
        settleUntil = 0;
        ceiling = 0;
        ceilingAt = 0;
        pendingRevertTo = 0;
        fpsBeforeCut = 0;
        giveUpUntil = 0;
        nextSample = 0;
    }

    /** Driven every client tick from {@link FarTerrainManager#tick}; samples once a second. */
    public static void tick(Minecraft minecraft) {
        SBSConfig.FarTerrainSettings settings = cfg();
        if (!settings.autoFps || minecraft.level == null) {
            if (applied > 0 || misses > 0) {
                reset();
            }
            return;
        }
        tickCounter++;
        if (tickCounter < nextSample || tickCounter < settleUntil) {
            return;
        }
        nextSample = tickCounter + SAMPLE_TICKS;

        int target = Math.max(MIN_TARGET, Math.min(MAX_TARGET, settings.targetFps));
        int slider = minecraft.options.renderDistance().get();
        int floor = Math.max(MIN_CHUNKS, FarTerrainManager.serverRadius());
        if (applied <= 0) {
            applied = slider;
        }
        int fps = minecraft.getFps();
        if (fps <= 0) {
            return;
        }

        // A cut is on probation until the next sample proves it bought something.
        if (pendingRevertTo > 0) {
            judgeLastCut(fps, slider);
            return;
        }
        if (ceiling > 0 && tickCounter - ceilingAt > CEILING_TTL_TICKS) {
            ceiling = 0;
        }

        if (fps < target * MISS_RATIO) {
            if (tickCounter < giveUpUntil) {
                return;   // already established that this is not the terrain
            }
            if (++misses < MISSES_BEFORE_CUT) {
                return;
            }
            misses = 0;
            cut(fps, target, floor);
            return;
        }
        misses = 0;
        raiseIfThereIsRoom(fps, target, slider);
    }

    /**
     * Gives up distance, proportionally to how far off the target is - a framerate at half the
     * target should not be corrected two chunks at a time.
     */
    private static void cut(int fps, int target, int floor) {
        int step = (int) Math.ceil(applied * (1.0 - fps / (double) target) * 0.5);
        step = Math.max(1, Math.min(MAX_CUT, step));
        int next = Math.max(floor, applied - step);
        if (next == applied) {
            return;   // already at the floor; nothing left to give
        }
        ceiling = applied;
        ceilingAt = tickCounter;
        pendingRevertTo = applied;
        fpsBeforeCut = fps;
        applied = next;
        settleUntil = tickCounter + SETTLE_TICKS;
    }

    /**
     * Decides whether the last cut earned its keep. This is what stops the controller stripping the
     * view away chasing a framerate that was never terrain-bound in the first place.
     */
    private static void judgeLastCut(int fps, int slider) {
        int before = fpsBeforeCut;
        int revertTo = pendingRevertTo;
        pendingRevertTo = 0;
        fpsBeforeCut = 0;
        if (before <= 0) {
            return;
        }
        if (fps >= before * (1.0 + CUT_MUST_GAIN)) {
            return;   // it helped - keep it and carry on
        }
        // It did not. Hand the distance back and stop trying for a while.
        applied = Math.min(slider, revertTo);
        ceiling = 0;
        giveUpUntil = tickCounter + GIVE_UP_TICKS;
        settleUntil = tickCounter + SETTLE_TICKS;
        sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Terrain] auto-fps: cutting to {} ch did not help ({} -> {} fps) - "
                        + "restored {} ch, the framerate is not the terrain's",
                applied, before, fps, revertTo);
    }

    /** Climbs back toward the player's own setting, slowly and never past a learned ceiling. */
    private static void raiseIfThereIsRoom(int fps, int target, int slider) {
        if (applied >= slider) {
            applied = slider;
            return;
        }
        if (fps < target * HEADROOM_RATIO
                || tickCounter - lastRaise < RAISE_INTERVAL_TICKS
                || (ceiling > 0 && applied + RAISE_STEP >= ceiling)) {
            return;
        }
        applied = Math.min(slider, applied + RAISE_STEP);
        lastRaise = tickCounter;
        settleUntil = tickCounter + SETTLE_TICKS;
    }
}
