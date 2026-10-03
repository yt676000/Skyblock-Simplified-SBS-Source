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
import org.joml.Vector3f;
import org.joml.Vector3fc;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Dark Mode: the three world-wide ways to take the glare out of the game - pick the time of day for
 * yourself, light everything uniformly at a chosen level, and darken every block.
 *
 * <p>Everything here is client-side and purely visual. The server is never told, nothing is written
 * to the player's options, and no resource pack is touched, so there is no state to restore and none
 * to strand.
 *
 * <p><b>Why one class for three effects.</b> They are three hooks in three different parts of the
 * frame - the clock, the lightmap and the chunk mesher - but they are one setting page and one
 * mental model ("make the world darker"), and two of the three are a single live config read. The
 * class is split into a section per hook below, each with the seam it sits on named.
 *
 * <p><b>Precedence.</b> The block darkening is the <i>fallback</i> layer: {@link EndVisuals} and
 * {@link MistVisuals} both claim the quads they care about first, and only what neither of them
 * wanted reaches {@link #shade}. That is what makes the island effects overrides - on The End the
 * end stone still darkens to the End's own setting and the purple still glows, while everything else
 * in view follows Dark Mode. Both shade mixins spell the chain out; a future effect of this kind
 * joins it by claiming its quads the same way.
 */
public final class DarkMode {

    private DarkMode() {
    }

    private static SBSConfig.DarkModeSettings cfg() {
        return ConfigManager.getInstance().get().darkMode;
    }

    /** A stored percentage as a 0..1 fraction, tolerating an out-of-range value in an edited config. */
    private static float percent(int stored) {
        return Math.max(0, Math.min(100, stored)) / 100f;
    }

    // ------------------------------------------------------------------ client-side time

    /** Ticks in one full day-night cycle. */
    private static final long DAY_TICKS = 24000L;

    /**
     * The wall-clock hour Minecraft's tick 0 corresponds to. The cycle starts at sunrise, so 06:00 is
     * tick 0, noon is 6000, sunset 12000 and midnight 18000 - the numbers the {@code /time} command
     * takes, and the reason the setting is stored as an hour rather than as a raw tick count.
     */
    private static final long SUNRISE_HOUR = 6L;

    /**
     * The tick count the world clock should report, or {@code -1} to leave it alone.
     *
     * <p>Read by {@code DarkModeClockMixin} on {@code ClientClockManager.getTotalTicks}, which is the
     * one place the client answers "what time is it". Everything time-driven hangs off it through
     * {@code AttributeTrackSampler}: the sun and moon angles, the star brightness, the sky and fog
     * colours, the sky light factor and the ambient light colour. Overriding it there is therefore a
     * real change of time of day rather than a recoloured sky - the sun actually stands where the
     * setting says it does.
     *
     * <p>Returning a constant also costs nothing per frame: the sampler caches its value per game
     * tick, so a fixed time is sampled once a tick and reused.
     */
    public static long clockOverride() {
        SBSConfig.DarkModeSettings cfg = cfg();
        if (!cfg.clientTime) {
            return -1L;
        }
        return ticksForHour(cfg.timeOfDay);
    }

    /** The tick offset into the day cycle for a wall-clock hour; 0:00 is midnight, 18000 ticks. */
    public static long ticksForHour(int hour) {
        return Math.floorMod((Math.floorMod(hour, 24) - SUNRISE_HOUR) * 1000L, DAY_TICKS);
    }

    // ------------------------------------------------------------------ uniform brightness

    /**
     * The darkest level the lightmap may be set to.
     *
     * <p>Not cosmetic: {@code lightmap.fsh}'s {@code notGamma()} divides by the largest channel of
     * the colour it is handed, so a lightmap of exactly zero is a division by zero and the whole
     * lightmap texture comes out NaN. One 255th is black to the eye and finite to the shader.
     */
    private static final float MIN_LEVEL = 0.004f;

    /**
     * How brightly the world should be lit, uniformly, as a 0..1 fraction - or {@code -1} while the
     * effect is off.
     *
     * <p>Read by {@code DarkModeLightmapMixin} at the tail of the lightmap extraction. "Uniform" is
     * meant literally: the shader builds every texel as
     * {@code max(AmbientColor, NightVisionColor * NightVisionFactor) + SkyLightColor * sky +
     * BlockLightColor * block}, so zeroing the sky and block factors and handing it a flat grey
     * ambient colour makes every texel exactly that grey. Light level stops affecting how bright
     * anything looks at all - which is the point: a cave, a lit island and a dungeon corridor all
     * read the same, at whatever level was chosen.
     *
     * <p>That is also why it is not simply "gamma with a bigger range". Gamma arrives in the shader
     * as a {@code mix()} weight towards a brightened version of the colour it already computed, so it
     * cannot brighten black and cannot flatten anything.
     */
    public static float uniformLevel() {
        SBSConfig.DarkModeSettings cfg = cfg();
        if (!cfg.uniformBrightness) {
            return -1f;
        }
        return Math.max(MIN_LEVEL, percent(cfg.brightness));
    }

    /** The flat ambient colour for a uniform level; allocated once per lightmap update, not per frame. */
    public static Vector3fc uniformColor(float level) {
        return new Vector3f(level, level, level);
    }

    // ------------------------------------------------------------------ block darkening

    /**
     * What the chunk mesher should do to a quad no other effect claimed; {@code null} means the
     * darkening is off and nothing is touched at all.
     *
     * @param blockTint ARGB grey every remaining block quad is multiplied by
     */
    public record Shading(int blockTint) {
    }

    /** Published by the client thread, read by every chunk-build worker. */
    private static volatile Shading shading;

    /**
     * How many quads the mesher actually darkened, counted on the worker threads and reported through
     * the {@code [SBS][DarkMode]} log every few seconds while active. Counted per pipeline for the
     * reason the End counters are: with Fabric API installed all terrain meshes through its renderer
     * and a vanilla count of zero is normal there - a fabric count of zero is the bug.
     */
    private static final AtomicLong DARKENED_QUADS = new AtomicLong();
    private static final AtomicLong DARKENED_QUADS_FABRIC = new AtomicLong();

    /** Client ticks since the last counter report; reports go out every 200 ticks (10s). */
    private static int reportTicks;

    /**
     * Darkens one block quad on the vanilla meshing pipeline.
     *
     * <p>Called only for quads {@link EndVisuals} and {@link MistVisuals} did not claim, so an island
     * effect always wins over the global one. Returns on a single volatile read and a branch while
     * the effect is off, which keeps it off the cost sheet for everyone not using it.
     */
    public static void shade(BlockState state, BlockGetter level, BlockPos pos, QuadInstance quad) {
        Shading active = shading;
        if (active == null) {
            return;
        }
        quad.multiplyColor(active.blockTint());
        DARKENED_QUADS.incrementAndGet();
    }

    /**
     * The same darkening for a quad meshed through Fabric API's renderer - the pipeline that actually
     * runs whenever Fabric API is installed. Colours use the same ARGB format, only the accessors
     * differ.
     */
    public static void shade(BlockState state, BlockGetter level, BlockPos pos, MutableQuadView quad) {
        Shading active = shading;
        if (active == null) {
            return;
        }
        for (int vertex = 0; vertex < 4; vertex++) {
            quad.color(vertex, ARGB.multiply(quad.color(vertex), active.blockTint()));
        }
        DARKENED_QUADS_FABRIC.incrementAndGet();
    }

    /**
     * Re-reads the settings and republishes the snapshot, rebuilding the world geometry when it
     * changed.
     *
     * <p>The rebuild is the expensive part, so it only happens on a genuine change: turning the
     * toggle on or off, or moving the slider. The other two effects need no tick at all - the clock
     * and the lightmap are asked live, every tick, by the code that uses them.
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
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null) {
            // 26.2's home of the old LevelRenderer.allChanged(): drops every compiled section so they
            // are meshed again under the new rules.
            minecraft.levelExtractor.allChanged();
        }
    }

    /** The snapshot the settings add up to, or {@code null} for "off". */
    private static Shading compute() {
        SBSConfig.DarkModeSettings cfg = cfg();
        if (!cfg.darkenBlocks) {
            return null;
        }
        int tint = greyTint(1f - percent(cfg.blockDarkness));
        // A tint of pure white would be a no-op multiply; treat the bottom of the slider as off so
        // the mesher is not walked for nothing.
        return tint == 0xFFFFFFFF ? null : new Shading(tint);
    }

    /** The multiply colour for the requested brightness: white at 1 (untouched), black at 0. */
    private static int greyTint(float brightness) {
        int value = Math.round(255 * brightness);
        return ARGB.color(255, value, value, value);
    }

    /**
     * While the darkening is active, reports every 10s how many quads the mesher touched since the
     * last report - zeros included, because "the line says 0" and "there is no line" are different
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
                "[SBS][DarkMode] last 10s of meshing: {} quads darkened on the fabric path, {} on the"
                        + " vanilla path (quads claimed by The End or The Mist are not counted here)",
                DARKENED_QUADS_FABRIC.getAndSet(0), DARKENED_QUADS.getAndSet(0));
    }

    /** Logs every change of the published snapshot, so the live game traces on, off and slider moves. */
    private static void logSnapshotChange(Shading next) {
        DARKENED_QUADS.set(0);
        DARKENED_QUADS_FABRIC.set(0);
        reportTicks = 0;
        if (next == null) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][DarkMode] block darkening OFF");
            return;
        }
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][DarkMode] block darkening ON: blockTint=#{}",
                Integer.toHexString(next.blockTint()));
    }
}
