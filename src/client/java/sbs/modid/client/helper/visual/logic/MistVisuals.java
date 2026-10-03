/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.visual.logic;

import com.mojang.blaze3d.vertex.QuadInstance;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.location.SkyBlockLocation;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The two Mist ghost-grinding effects: the blinding white aura of the ghosts (charged creepers) is
 * dimmed, and the white blocks the pit is built out of - snow, white glass, wool, quartz - stop
 * glaring. Both are brightness sliders, both only run in The Mist, the pit below the Dwarven Mines
 * where the ghosts spawn.
 *
 * <p><b>The ghost aura</b> is vanilla's charged-creeper energy swirl - a SkyBlock ghost is an
 * invisible creeper whose charge layer is the only thing you see, and hours of staring into that
 * white glare is what the slider is for. {@code GhostSwirlDimMixin} catches the one place the layer
 * submits its model ({@code EnergySwirlLayer.submit}'s {@code submitModel} call) and runs the
 * hard-coded model colour through {@link #swirlColor}, which scales it towards black. Per frame and
 * per powered creeper, so the cost is a volatile read even while active.
 *
 * <p><b>The white pit blocks</b> ride exactly the seam the End block effects ride ({@link
 * EndVisuals}): every block quad passes the chunk mesher once, {@code EndBlockShadeMixin} /
 * {@code EndBlockShadeFabricMixin} hand it over, and {@link #shade} multiplies a grey onto the quads
 * of the white {@linkplain MapColor map-colour} family. Paid once per chunk build on the worker
 * thread that was building it anyway, nothing per frame.
 *
 * <p><b>Gating.</b> The blocks are dimmed only while the scoreboard's zone is The Mist - the same
 * white blocks build the Great Ice Wall and half of everyone's snow builds, and only the pit was
 * asked for. The aura is dimmed there too, and additionally in a singleplayer world while SkyBlock
 * publishes no location at all, because the only charged creeper such a world ever contains is one
 * spawned on purpose to tune this slider.
 *
 * <p><b>Threading.</b> Same contract as {@link EndVisuals}: chunks mesh on worker threads and the
 * swirl submits on the render thread, so neither may read the scoreboard. {@link #onClientTick()}
 * computes everything on the client thread and publishes one immutable {@link Shading} through a
 * volatile; a change of the block half drops the compiled geometry (the real F3+A) so the pit
 * re-darkens within a tick instead of chunk by chunk.
 */
public final class MistVisuals {

    /** The zone the ghosts spawn in, as the sidebar spells it. */
    private static final String MIST_ZONE = "The Mist";

    /** The island the pit belongs to - the tab list's {@code Area:} line, which always reads. */
    private static final String MIST_ISLAND = "Dwarven Mines";

    private MistVisuals() {
    }

    /**
     * What the two hooks should do, as one immutable snapshot; {@code null} means both effects are
     * off or out of area, and nothing is touched at all.
     *
     * @param blockTint       ARGB grey the white pit blocks are multiplied by (0 = leave the blocks)
     * @param swirlBrightness how much of the aura's vanilla colour survives, 0..1 (negative = leave
     *                        the aura)
     */
    public record Shading(int blockTint, float swirlBrightness) {
    }

    /** Published by the client thread; read by chunk-build workers and the render thread. */
    private static volatile Shading shading;

    /**
     * How much each hook actually touched, counted where it happens and reported through the
     * {@code [SBS][Mist]} log every few seconds while active - the line that separates "the hook
     * never matches" from "the edit is consumed but invisible" without a debugger in the live game.
     * Blocks are counted per pipeline for the same reason the End counters are: with Fabric API
     * installed all terrain meshes through its renderer and a vanilla count of zero is normal there.
     */
    private static final AtomicLong DIMMED_QUADS = new AtomicLong();
    private static final AtomicLong DIMMED_QUADS_FABRIC = new AtomicLong();
    private static final AtomicLong DIMMED_SWIRLS = new AtomicLong();

    /** Client ticks since the last counter report; reports go out every 200 ticks (10s). */
    private static int reportTicks;

    /** Ticks a toggle has spent waiting on a closed gate on the right island; for the 10s trace. */
    private static int gateTicks;

    // ------------------------------------------------------------------ the hooks' side

    /**
     * Replaces the colour the charged-creeper aura's model is submitted with - vanilla passes an
     * opaque half-grey - by the same colour scaled towards black. Called per frame for every powered
     * creeper on screen; returns the input untouched on a single volatile read while off.
     */
    public static int swirlColor(int color) {
        Shading active = shading;
        if (active == null || active.swirlBrightness() < 0f) {
            return color;
        }
        DIMMED_SWIRLS.incrementAndGet();
        return ARGB.scaleRGB(color, active.swirlBrightness());
    }

    /**
     * Dims one block quad on the vanilla meshing pipeline; same single-branch exit while off.
     *
     * @return whether this quad was <i>claimed</i>: the zone effect is an override, so a quad it
     *         dimmed is finished and the world-wide {@link DarkMode} darkening must leave it alone.
     *         The shade mixins spell that chain out.
     */
    public static boolean shade(BlockState state, BlockGetter level, BlockPos pos, QuadInstance quad) {
        Shading active = shading;
        if (active == null || active.blockTint() == 0) {
            return false;
        }
        if (isWhite(state.getMapColor(level, pos))) {
            quad.multiplyColor(active.blockTint());
            DIMMED_QUADS.incrementAndGet();
            return true;
        }
        return false;
    }

    /**
     * The same dim for a quad meshed through Fabric API's renderer - the pipeline that actually runs
     * whenever Fabric API is installed. Colours use the same ARGB format, only the accessors differ.
     *
     * @return whether this quad was claimed, on the same contract as the vanilla overload
     */
    public static boolean shade(BlockState state, BlockGetter level, BlockPos pos, MutableQuadView quad) {
        Shading active = shading;
        if (active == null || active.blockTint() == 0) {
            return false;
        }
        if (isWhite(state.getMapColor(level, pos))) {
            for (int vertex = 0; vertex < 4; vertex++) {
                quad.color(vertex, ARGB.multiply(quad.color(vertex), active.blockTint()));
            }
            DIMMED_QUADS_FABRIC.incrementAndGet();
            return true;
        }
        return false;
    }

    /**
     * The white family the pit is built out of, by the map colour Minecraft itself files every block
     * under: {@link MapColor#SNOW} is snow, white wool, white glass, white concrete and carpet;
     * {@link MapColor#WOOL} is cobwebs and white beds; {@link MapColor#QUARTZ} quartz, diorite and
     * birch log sides; {@link MapColor#ICE} the ices; {@link MapColor#TERRACOTTA_WHITE} calcite and
     * white terracotta. A handful of reference comparisons, cheap enough to run per quad.
     */
    private static boolean isWhite(MapColor color) {
        return color == MapColor.SNOW
                || color == MapColor.WOOL
                || color == MapColor.QUARTZ
                || color == MapColor.ICE
                || color == MapColor.TERRACOTTA_WHITE;
    }

    // ------------------------------------------------------------------ the client thread's side

    /**
     * Re-reads the settings and the location, and republishes the snapshot; a changed <i>block</i>
     * half also rebuilds the world geometry, which is the expensive part and the reason only a
     * genuine change triggers it - a moved slider, a toggle, or crossing the Mist boundary. A changed
     * aura half needs nothing: the swirl is re-coloured per frame anyway.
     */
    public static void onClientTick() {
        Shading next = compute();
        Shading previous = shading;
        if (Objects.equals(next, previous)) {
            reportCounters(next);
            return;
        }
        shading = next;
        logSnapshotChange(next);
        int previousTint = previous == null ? 0 : previous.blockTint();
        int nextTint = next == null ? 0 : next.blockTint();
        Minecraft minecraft = Minecraft.getInstance();
        if (previousTint != nextTint && minecraft.level != null) {
            // 26.2's home of the old LevelRenderer.allChanged(): drops every compiled section so they
            // are meshed again under the new rules.
            minecraft.levelExtractor.allChanged();
        }
    }

    /** The snapshot the settings and the current location add up to, or {@code null} for "off". */
    private static Shading compute() {
        SBSConfig.VisualsSettings cfg = ConfigManager.getInstance().get().visuals;
        boolean ghosts = cfg.dimGhosts;
        boolean blocks = cfg.dimMistBlocks;
        if (!ghosts && !blocks) {
            gateTicks = 0;
            return null;
        }
        boolean mist = inTheMist();
        traceClosedGate(mist);
        // The darkness slider is on the Dark End Blocks scale: 0 = natural, 100 = pitch black.
        int blockTint = blocks && mist ? greyTint(1f - percent(cfg.mistDarkness)) : 0;
        float swirl = ghosts && (mist || tuningWorld()) ? percent(cfg.ghostBrightness) : -1f;
        if (blockTint == 0 && swirl < 0f) {
            return null;
        }
        return new Shading(blockTint, swirl);
    }

    /** A stored percentage as a 0..1 fraction, tolerating an out-of-range value in an edited config. */
    private static float percent(int stored) {
        return Math.max(0, Math.min(100, stored)) / 100f;
    }

    /** The multiply colour for the requested brightness: white at 1 (untouched), black at 0. */
    private static int greyTint(float brightness) {
        int value = Math.round(255 * brightness);
        return ARGB.color(255, value, value, value);
    }

    /**
     * Whether the player is in The Mist - the zone-level question; the rest of the island is not.
     *
     * <p>Two reads, because the parsed zone alone proved to be nothing on the mining islands: {@code
     * SkyBlockLocation.zone()} only recognises a sidebar line by its {@code ⏣} marker, and Hypixel
     * writes the Dwarven zone line with a private-use glyph from its custom font instead (the action
     * bar provably does - {@code E067 §8The Mist} - and the sidebar went unmatched in the same
     * session). So when the parsed zone says nothing, the raw sidebar lines are scanned for the zone
     * <i>name</i>, marker be what it may - but only while the tab list places the player on the
     * right island, so a "The Mist" somewhere in another island's sidebar text cannot turn it on.
     *
     * <p>Public because it is the tested answer to "am I in the ghost pit", and every other ghost
     * feature needs the same one - {@code GhostTracker} gates on it. Two copies of a gate this
     * fiddly would drift, and one of them would be the broken one.
     */
    public static boolean inTheMist() {
        if (SkyBlockLocation.zone().toLowerCase(java.util.Locale.ROOT).contains("mist")) {
            return true;
        }
        if (!SkyBlockLocation.onIsland(MIST_ISLAND)) {
            return false;
        }
        for (String line : SkyBlockLocation.sidebarLines()) {
            if (line.contains(MIST_ZONE)) {
                return true;
            }
        }
        return false;
    }

    /**
     * While a toggle is on, the player is on the pit's island, and the gate still says no, reports
     * every 10s what the location reads actually returned - the parsed zone and the raw sidebar -
     * so a session in the pit that shows no effect also leaves the line that says exactly why.
     */
    private static void traceClosedGate(boolean mist) {
        if (mist || !SkyBlockLocation.onIsland(MIST_ISLAND)) {
            gateTicks = 0;
            return;
        }
        if (++gateTicks < 200) {
            return;
        }
        gateTicks = 0;
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Mist] gate closed on {}: zone='{}' sidebar={}",
                MIST_ISLAND, SkyBlockLocation.zone(), SkyBlockLocation.sidebarLines());
    }

    /**
     * A singleplayer world while SkyBlock publishes no location at all - the only place a charged
     * creeper is ever spawned on purpose, which makes it the place the aura slider is tuned. Only the
     * aura falls back to this: dimming every snow build in singleplayer would not be tuning.
     */
    private static boolean tuningWorld() {
        if (!SkyBlockLocation.island().isEmpty() || !SkyBlockLocation.zone().isEmpty()) {
            return false;
        }
        return Minecraft.getInstance().hasSingleplayerServer();
    }

    /**
     * While either effect is active, reports every 10s how much each hook touched since the last
     * report - zeros included, because "the line says 0" and "there is no line" are different
     * diagnoses (nothing matched vs. the snapshot is off).
     */
    private static void reportCounters(Shading active) {
        if (active == null) {
            reportTicks = 0;
            return;
        }
        if (++reportTicks < 200) {
            return;
        }
        reportTicks = 0;
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Mist] last 10s: {} quads dimmed on the fabric path, {} on the vanilla path,"
                        + " {} aura submits re-coloured",
                DIMMED_QUADS_FABRIC.getAndSet(0), DIMMED_QUADS.getAndSet(0),
                DIMMED_SWIRLS.getAndSet(0));
    }

    /** Logs every change of the published snapshot, so the live game traces on, off and slider moves. */
    private static void logSnapshotChange(Shading next) {
        DIMMED_QUADS.set(0);
        DIMMED_QUADS_FABRIC.set(0);
        DIMMED_SWIRLS.set(0);
        reportTicks = 0;
        if (next == null) {
            SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][Mist] shading OFF (left The Mist, or both toggles off)");
            return;
        }
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Mist] shading ON: blockTint=#{} swirlBrightness={} (zone='{}')",
                Integer.toHexString(next.blockTint()),
                String.format("%.2f", next.swirlBrightness()), SkyBlockLocation.zone());
    }
}
