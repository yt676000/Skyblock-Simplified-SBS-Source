/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.terrain;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import sbs.modid.client.core.config.ConfigManager;

/**
 * Measures how far the player can actually see, and while that is a cave-sized distance, quietly
 * narrows what the renderer is asked to draw.
 *
 * <p><b>The problem.</b> Far Terrain's whole cost is paid per frame whether or not any of it is on
 * screen: a slayer camp in the Coal Mine or a fight down a tunnel has the renderer walking and
 * culling a 32-64 chunk view of terrain that a wall of stone hides completely. That is frames spent
 * on nothing.
 *
 * <p><b>What this does about it.</b> Once a second (four times a second while it is already engaged)
 * it asks the world a simple question - how far away is the nearest solid block in each direction -
 * and hands the answer to {@link FarTerrainManager#ownRenderDistance()} as the distance to draw. In
 * the open the answer is "further than your slider" and nothing happens at all; boxed in, the
 * renderer is told the truth and stops working on the rest.
 *
 * <p><b>Nothing is unloaded.</b> This narrows <i>drawing</i>, never the live window - every chunk
 * stays in memory, meshed and ready, so the moment the measurement opens up again the full view is
 * back on the next frame with nothing to decode, stream or rebuild. That is the whole reason the
 * clamp lives here and not in {@link FarTerrainManager#liveRadius()}.
 *
 * <p><b>It can never be worse than not having the module.</b> The floor is the server's own chunk
 * radius, so the narrowest this will ever draw is exactly what vanilla would have drawn while
 * standing in the same spot.
 *
 * <p><b>Why a measurement and not a list of places.</b> An island or area whitelist gets this wrong
 * in both directions: Revenants are fought in the open-air Graveyard as often as in the Coal Mine,
 * and the Dwarven Mines have caverns where the far view is genuinely worth drawing. Measuring costs
 * a few thousand block lookups a second and is right everywhere, including places Hypixel adds
 * later.
 */
public final class FarTerrainEnclosure {

    /** How often the world is measured while the view is open, in ticks. */
    private static final int SAMPLE_TICKS_IDLE = 20;

    /**
     * How often it is measured while already narrowed. Four times a second, because this is the
     * delay between stepping out of a cave and the full view returning - the one moment the player
     * could notice this feature exists.
     */
    private static final int SAMPLE_TICKS_ENGAGED = 5;

    /**
     * How long the view has to stay boxed in before the clamp engages. Long enough that running
     * through a tunnel, a doorway or a dungeon corridor never triggers it, short enough that sitting
     * down to grind picks it up almost immediately.
     */
    private static final int ENGAGE_TICKS = 100;

    /** How far a probe ray looks before giving up and calling that direction open. */
    private static final double PROBE_BLOCKS = 256.0;

    private static final int HORIZONTAL_RAYS = 12;
    private static final int RAISED_RAYS = 4;

    /**
     * "A bit extra" on top of what was measured, in chunks. The measurement is where the rock is;
     * drawing has to reach past it, or the cull edge and the fog plane land in front of the wall
     * instead of behind it and the narrowing becomes visible.
     */
    private static final int DRAW_MARGIN = 3;

    /**
     * The clamp grows instantly but shrinks at most this many chunks per sample. Snapping down would
     * pull the fog in as a visible sweep the moment a door closes behind you; easing down hides it
     * in the second the eye is still adjusting to the cave.
     */
    private static final int SHRINK_PER_SAMPLE = 4;

    /** Never draw less than this, whatever the server says its radius is. */
    private static final int MIN_CHUNKS = 4;

    /** Sky openings in the sampled 5x5 columns above which the raycast is not worth doing. */
    private static final int OPEN_SKY_COLUMNS = 5;

    private static int tickCounter;
    private static int nextSample;
    private static int enclosedTicks;
    private static boolean engaged;

    /** The clamp actually in force, in chunks; only meaningful while {@link #engaged}. */
    private static int applied;

    /** The last raw measurement, in chunks, for the settings status line. */
    private static int measured;

    private FarTerrainEnclosure() {
    }

    /**
     * The render distance to use right now, or {@code null} to leave the player's own setting alone.
     * Read by {@link FarTerrainManager#ownRenderDistance()} every frame, so it must stay a field
     * read - the measuring happens in {@link #tick}.
     */
    public static Integer viewChunks() {
        return engaged ? applied : null;
    }

    /** Whether the view is currently narrowed, for the settings status line. */
    public static boolean engaged() {
        return engaged;
    }

    /** What the last measurement said, in chunks - the number the clamp is derived from. */
    public static int measuredChunks() {
        return measured;
    }

    /** Forgets everything; called when the level goes away so a new world starts open. */
    public static void reset() {
        enclosedTicks = 0;
        engaged = false;
        applied = 0;
        measured = 0;
        nextSample = 0;
    }

    /**
     * Driven from {@link FarTerrainManager#tick}. Cheap on the ticks it does not sample, and the
     * ticks it does sample cost a few thousand block lookups.
     */
    public static void tick(Minecraft minecraft) {
        if (!ConfigManager.getInstance().get().farTerrain.pauseWhenEnclosed) {
            if (engaged) {
                reset();
            }
            return;
        }
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        if (level == null || player == null) {
            reset();
            return;
        }
        tickCounter++;
        if (tickCounter < nextSample) {
            return;
        }
        nextSample = tickCounter + (engaged ? SAMPLE_TICKS_ENGAGED : SAMPLE_TICKS_IDLE);

        int slider = Minecraft.getInstance().options.renderDistance().get();
        int floor = Math.max(MIN_CHUNKS, FarTerrainManager.serverRadius());
        measured = measure(level, player);

        // Open: release immediately and forget the hold. Being slow to give the view back is the
        // only way this feature can annoy anyone, so it never is.
        int target = measured >= Integer.MAX_VALUE - DRAW_MARGIN
                ? slider : Math.min(slider, Math.max(floor, measured + DRAW_MARGIN));
        if (target >= slider) {
            enclosedTicks = 0;
            engaged = false;
            applied = slider;
            return;
        }

        enclosedTicks += engaged ? SAMPLE_TICKS_ENGAGED : SAMPLE_TICKS_IDLE;
        if (!engaged) {
            if (enclosedTicks < ENGAGE_TICKS) {
                return;
            }
            engaged = true;
            applied = slider;   // ease down from where the view was, not straight to the wall
        }
        applied = target > applied ? target : Math.max(target, applied - SHRINK_PER_SAMPLE);
    }

    /**
     * How far the player can see, in chunks, or {@link Integer#MAX_VALUE} for "as far as you like".
     *
     * <p>Two steps, cheapest first. Open sky overhead settles it without a single ray - and being
     * wrong there is free, because the answer only ever skips the optimisation. Otherwise the
     * distance is the <b>upper quartile</b> of the probe rays: one open tunnel mouth must not count
     * as a view, but a cavern with most of its directions open has to.
     */
    private static int measure(ClientLevel level, LocalPlayer player) {
        BlockPos base = player.blockPosition();
        int openColumns = 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (level.canSeeSky(base.offset(dx, 0, dz))) {
                    openColumns++;
                }
            }
        }
        if (openColumns >= OPEN_SKY_COLUMNS) {
            return Integer.MAX_VALUE;
        }

        Vec3 eye = player.getEyePosition();
        double[] hits = new double[HORIZONTAL_RAYS + RAISED_RAYS];
        int index = 0;
        for (int i = 0; i < HORIZONTAL_RAYS; i++) {
            double angle = (Math.PI * 2.0 * i) / HORIZONTAL_RAYS;
            hits[index++] = probe(level, player, eye, Math.cos(angle), 0.0, Math.sin(angle));
        }
        // A handful pitched up as well: a ceiling is what makes a cave a cave, and a room whose
        // walls are close but whose roof is open is not one.
        for (int i = 0; i < RAISED_RAYS; i++) {
            double angle = (Math.PI * 2.0 * i) / RAISED_RAYS + Math.PI / RAISED_RAYS;
            hits[index++] = probe(level, player, eye,
                    Math.cos(angle) * 0.866, 0.5, Math.sin(angle) * 0.866);
        }
        java.util.Arrays.sort(hits);
        double upperQuartile = hits[(hits.length * 3) / 4];
        if (upperQuartile >= PROBE_BLOCKS) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.ceil(upperQuartile / 16.0);
    }

    /** Distance to the first solid block along a direction, or {@link #PROBE_BLOCKS} for none. */
    private static double probe(ClientLevel level, LocalPlayer player, Vec3 eye,
                                double x, double y, double z) {
        Vec3 target = eye.add(x * PROBE_BLOCKS, y * PROBE_BLOCKS, z * PROBE_BLOCKS);
        HitResult hit = level.clip(new ClipContext(eye, target,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.BLOCK
                ? hit.getLocation().distanceTo(eye) : PROBE_BLOCKS;
    }
}
