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
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.location.SkyBlockLocation;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The two End-island block effects: purple and pink blocks glow, the pale blocks the End is built out
 * of stop being blindingly bright.
 *
 * <p><b>Why the chunk mesh and not an overlay.</b> The End is made of thousands of blocks, so drawing
 * a highlight per block through the HUD projection ({@code WorldRender}) would cost a fill per edge
 * every frame. Both effects are instead folded into the geometry the chunk mesher already builds:
 * {@code EndBlockShadeMixin} sits on {@code ModelBlockRenderer.putQuadWithTint}, the single point every
 * block quad passes through just before it is written to the section buffer, and this class decides
 * what to do with it. The cost is paid once per chunk build, on the worker thread that was building it
 * anyway, and nothing at all is paid per frame.
 *
 * <p><b>Glow</b> is three things at once, because the obvious one alone is not enough. Raising the
 * quad's <i>block</i> light ({@link LightCoordsUtil#addSmoothBlockEmission}) is what lights a block up
 * from the inside - but a fragment is {@code texture x vertexColour x lightmap}, and all three cap at
 * white, so where the End is already fully lit (Hypixel lights its islands, and the mod's own
 * Fullbright floors the whole lightmap at white) extra light has nowhere left to go and changes
 * literally nothing. The other two work regardless of how bright it already is: the vertex colours are
 * pulled towards white, which strips the directional face shading and the ambient occlusion so every
 * face reads equally bright - the look of a block that lights itself - and a magenta tint is
 * multiplied on, which saturates the block's own colour. Downwards is the only headroom a fully lit
 * scene leaves, so "brighter" has to be spent on "more vivid".
 *
 * <p><b>Dark end blocks</b> re-tints the pale family - {@link MapColor#SAND} and {@link
 * MapColor#QUARTZ}, which is end stone, end stone bricks, sand, sandstone, birch and quartz alike -
 * without shipping or downloading any texture, and without touching the pack the player actually
 * uses. Two things happen while the toggle is on. The yellow cast is <i>always</i> removed: a vertex
 * colour can only multiply, so saturation is cancelled by multiplying with the cast's complement -
 * {@link #DESAT_R}/{@link #DESAT_G}/{@link #DESAT_B}, computed from the real texture averages so end
 * stone lands within a few points of neutral grey. And the slider is reverse brightness on top of
 * that: 0% leaves the blocks their natural brightness (just grey instead of yellow), 100% is pitch
 * black.
 *
 * <p><b>Which blocks are affected</b> is answered by the block's {@linkplain MapColor map colour}, the
 * colour Minecraft itself files every block under: it covers whatever Hypixel built the End out of -
 * purpur, chorus, stained glass, wool, concrete, terracotta on the glowing side; end stone, sand,
 * birch planks and slabs on the darkened one - without a hand-written list that would silently miss
 * one. Obsidian is black, so it neither glows nor darkens.
 *
 * <p><b>Threading.</b> Chunks are meshed on worker threads, so nothing here may read the scoreboard or
 * the tab list: {@link #onClientTick()} computes the whole decision on the client thread and publishes
 * it as one immutable {@link Shading} through a volatile field. The mesher only reads that snapshot.
 * A changed snapshot means the sections in memory were built under the old rules, so the tick also
 * asks for a full geometry rebuild - the same thing F3+A does - which is why toggling a setting or
 * warping into the End applies within a tick instead of when the chunk happens to be rebuilt.
 */
public final class EndVisuals {

    /** The island both effects run on; every zone of it (Dragon's Nest, Void Sepulture, ...) counts. */
    private static final String END_ISLAND = "The End";

    /**
     * The multiply colour that cancels the pale family's yellow cast at full brightness.
     *
     * <p>Sampled, not guessed: the four defining textures average end stone (220,223,158), end stone
     * bricks (218,224,162), sand (219,207,163) and birch planks (192,175,121) - family mean
     * (212,207,151). Dividing each channel by the mean and normalising to the smallest (blue) gives
     * this tint; multiplied on, end stone renders (157,163,158) - neutral grey at its own natural
     * brightness, which is what "saturation zero, brightness untouched" means for a multiply-only
     * channel.
     */
    private static final int DESAT_R = 182;
    private static final int DESAT_G = 186;
    private static final int DESAT_B = 255;

    /** The colour a glowing block is multiplied by at 100% intensity - pure magenta, full depth. */
    private static final int GLOW_R = 255;
    private static final int GLOW_G = 0;
    private static final int GLOW_B = 255;

    /** White, the colour a glowing block's own shading is pulled towards. */
    private static final int UNSHADED = 0xFFFFFFFF;

    private EndVisuals() {
    }

    /**
     * What the chunk mesher should do to a quad, as one immutable snapshot; {@code null} means the
     * feature is off or the player is not in the End, and nothing is touched at all.
     *
     * @param emission how much block light purple/pink blocks emit, 0..1 (0 = the glow is off)
     * @param flatten  how far their vertex colours are pulled to white, 0..1 (1 = no shading left)
     * @param glowTint ARGB colour they are then multiplied by (white = leave the colour alone)
     * @param darkTint ARGB colour the pale blocks are multiplied by (0 = leave them alone)
     */
    public record Shading(float emission, float flatten, int glowTint, int darkTint) {
    }

    /** Published by the client thread, read by every chunk-build worker. */
    private static volatile Shading shading;

    /**
     * How many quads the mesher actually touched, counted on the worker threads and reported through
     * the {@code [SBS][End]} log every few seconds while the feature is active. This is the line that
     * separates "the hook never matches anything" from "the quads are edited but the screen does not
     * change" without a debugger in the live game - the difference decides where the bug is. Counted
     * per pipeline, because which of the two hooks is live is exactly what went undiagnosed once:
     * with Fabric API installed all terrain meshes through its renderer and the vanilla hook is dead
     * code, so a vanilla-only count of zero is normal there - and a fabric count of zero is the bug.
     */
    private static final AtomicLong DARKENED_QUADS = new AtomicLong();
    private static final AtomicLong GLOWING_QUADS = new AtomicLong();
    private static final AtomicLong DARKENED_QUADS_FABRIC = new AtomicLong();
    private static final AtomicLong GLOWING_QUADS_FABRIC = new AtomicLong();

    /** Client ticks since the last counter report; reports go out every 200 ticks (10s). */
    private static int reportTicks;

    // ------------------------------------------------------------------ the mesher's side

    /**
     * Applies the End effects to one block quad, called for every quad the chunk mesher builds.
     *
     * <p>Returns immediately - a single volatile read and a branch - whenever the feature is off,
     * which is what keeps it off the cost sheet for everyone not using it.
     *
     * @return whether this quad was <i>claimed</i>: the island effects are overrides, so a quad they
     *         touched is finished and the world-wide {@link DarkMode} darkening must leave it alone.
     *         The shade mixins spell that chain out.
     */
    public static boolean shade(BlockState state, BlockGetter level, BlockPos pos, QuadInstance quad) {
        Shading active = shading;
        if (active == null) {
            return false;
        }
        // A plain field read on the state - the whole decision for both effects is this one lookup.
        MapColor color = state.getMapColor(level, pos);
        if (active.darkTint() != 0 && isPale(color)) {
            quad.multiplyColor(active.darkTint());
            DARKENED_QUADS.incrementAndGet();
            return true;
        }
        if (active.emission() > 0f && isPurpleOrPink(color)) {
            for (int vertex = 0; vertex < 4; vertex++) {
                quad.setLightCoords(vertex, LightCoordsUtil.addSmoothBlockEmission(
                        quad.getLightCoords(vertex), active.emission()));
                // Strips the face shading and the ambient occlusion: a self-lit block is equally
                // bright on every side, which is what reads as "glowing" once the light is capped.
                quad.setColor(vertex, ARGB.srgbLerp(active.flatten(), quad.getColor(vertex), UNSHADED));
            }
            quad.multiplyColor(active.glowTint());
            GLOWING_QUADS.incrementAndGet();
            return true;
        }
        return false;
    }

    /**
     * The same effects for a quad meshed through Fabric API's renderer ({@code
     * EndBlockShadeFabricMixin}) - the pipeline that actually runs whenever Fabric API is installed.
     * Colours and lightmaps use the same formats as the vanilla path (ARGB, packed light coords), so
     * the transform is identical; only the accessors differ.
     *
     * @return whether this quad was claimed, on the same contract as the vanilla overload
     */
    public static boolean shade(BlockState state, BlockGetter level, BlockPos pos, MutableQuadView quad) {
        Shading active = shading;
        if (active == null) {
            return false;
        }
        MapColor color = state.getMapColor(level, pos);
        if (active.darkTint() != 0 && isPale(color)) {
            for (int vertex = 0; vertex < 4; vertex++) {
                quad.color(vertex, ARGB.multiply(quad.color(vertex), active.darkTint()));
            }
            DARKENED_QUADS_FABRIC.incrementAndGet();
            return true;
        }
        if (active.emission() > 0f && isPurpleOrPink(color)) {
            for (int vertex = 0; vertex < 4; vertex++) {
                quad.lightmap(vertex, LightCoordsUtil.addSmoothBlockEmission(
                        quad.lightmap(vertex), active.emission()));
                // Same flatten-then-tint as the vanilla path: strip the face shading and AO, then
                // saturate - the halves of the glow that still show where the light is already full.
                int flattened = ARGB.srgbLerp(active.flatten(), quad.color(vertex), UNSHADED);
                quad.color(vertex, ARGB.multiply(flattened, active.glowTint()));
            }
            GLOWING_QUADS_FABRIC.incrementAndGet();
            return true;
        }
        return false;
    }

    /**
     * The bright, pale family the End is paved and built with: {@link MapColor#SAND} is end stone,
     * end stone bricks and their slabs, stairs and walls, but also sand, sandstone and birch planks
     * and slabs, and {@link MapColor#QUARTZ} is quartz, diorite and the sides of birch logs - so a
     * birch build darkens as one piece instead of half of it staying bright.
     */
    private static boolean isPale(MapColor color) {
        return color == MapColor.SAND || color == MapColor.QUARTZ;
    }

    /**
     * Whether the block counts as purple or pink, by the map colour Minecraft files it under. Six
     * reference comparisons, so this stays cheap even though it runs per quad.
     */
    private static boolean isPurpleOrPink(MapColor color) {
        return color == MapColor.COLOR_PURPLE
                || color == MapColor.COLOR_MAGENTA
                || color == MapColor.COLOR_PINK
                || color == MapColor.TERRACOTTA_PURPLE
                || color == MapColor.TERRACOTTA_MAGENTA
                || color == MapColor.TERRACOTTA_PINK;
    }

    // ------------------------------------------------------------------ the client thread's side

    /**
     * Re-reads the settings and the location, and rebuilds the world geometry when either changed.
     *
     * <p>The rebuild is the expensive part, so it only happens on a genuine change of the snapshot:
     * turning a setting on or off, moving a slider, and arriving in or leaving the End.
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

    /**
     * While the feature is active, reports every 10s how many quads the mesher darkened and how many
     * it made glow since the last report - zeros included, because "the line says 0" and "there is no
     * line" are different diagnoses (nothing matched vs. the snapshot is off).
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
                "[SBS][End] last 10s of meshing: fabric path {} darkened / {} glowing,"
                        + " vanilla path {} darkened / {} glowing",
                DARKENED_QUADS_FABRIC.getAndSet(0), GLOWING_QUADS_FABRIC.getAndSet(0),
                DARKENED_QUADS.getAndSet(0), GLOWING_QUADS.getAndSet(0));
    }

    /**
     * Logs every change of the published snapshot - which settings and location add up to what the
     * mesher is now told to do - so the live game leaves a full trace of on, off and slider moves.
     */
    private static void logSnapshotChange(Shading next) {
        DARKENED_QUADS.set(0);
        GLOWING_QUADS.set(0);
        DARKENED_QUADS_FABRIC.set(0);
        GLOWING_QUADS_FABRIC.set(0);
        reportTicks = 0;
        if (next == null) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][End] shading OFF (left the End, or both toggles off)");
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        String light = "?";
        if (minecraft.level != null && minecraft.player != null) {
            BlockPos pos = minecraft.player.blockPosition();
            light = "block " + minecraft.level.getBrightness(LightLayer.BLOCK, pos)
                    + ", sky " + minecraft.level.getBrightness(LightLayer.SKY, pos)
                    + " at " + pos.toShortString();
        }
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][End] shading ON: emission={} flatten={} glowTint=#{} darkTint=#{} (light: {};"
                        + " 15s mean the emission half has no headroom, the colour half always shows)",
                String.format("%.2f", next.emission()), String.format("%.2f", next.flatten()),
                Integer.toHexString(next.glowTint()), Integer.toHexString(next.darkTint()), light);
    }

    /** The snapshot the settings and the current location add up to, or {@code null} for "off". */
    private static Shading compute() {
        SBSConfig.VisualsSettings cfg = ConfigManager.getInstance().get().visuals;
        boolean glow = cfg.endBlockGlow;
        boolean dark = cfg.darkEndBlocks;
        if ((!glow && !dark) || !inTheEnd()) {
            return null;
        }
        float intensity = glow ? percent(cfg.endGlowIntensity) : 0f;
        return new Shading(intensity, intensity, glowTint(intensity),
                dark ? darkTint(percent(cfg.darkEndStrength)) : 0);
    }

    /** A stored percentage as a 0..1 fraction, tolerating an out-of-range value in an edited config. */
    private static float percent(int stored) {
        return Math.max(0, Math.min(100, stored)) / 100f;
    }

    /**
     * The multiply colour for the requested darkness: the de-saturating tint scaled by reverse
     * brightness - full grey brightness at 0, pitch black at 1. The yellow cast is gone at every
     * setting; the slider only chooses how dark the grey is.
     */
    private static int darkTint(float strength) {
        float brightness = 1f - strength;
        return ARGB.color(255,
                Math.round(DESAT_R * brightness),
                Math.round(DESAT_G * brightness),
                Math.round(DESAT_B * brightness));
    }

    /** The multiply colour for the requested glow: white at 0 (untouched), the hot magenta at 1. */
    private static int glowTint(float intensity) {
        return blend(intensity, GLOW_R, GLOW_G, GLOW_B);
    }

    /** White faded towards the given colour by {@code amount}; white is what "multiply" means as off. */
    private static int blend(float amount, int red, int green, int blue) {
        return ARGB.color(255,
                Math.round(255 - (255 - red) * amount),
                Math.round(255 - (255 - green) * amount),
                Math.round(255 - (255 - blue) * amount));
    }

    /**
     * Whether the player is on the End island.
     *
     * <p>{@link SkyBlockLocation} is the answer whenever SkyBlock is publishing one. Off SkyBlock -
     * singleplayer, another server, a Hypixel lobby - it publishes nothing, and the dimension is used
     * instead, so the effects can be seen and tuned in a normal End without a SkyBlock connection.
     * Deliberately not the other way round: several servers serve everything from one dimension, and
     * an island-wide "everything is the End" would darken the whole game.
     */
    private static boolean inTheEnd() {
        if (!SkyBlockLocation.island().isEmpty() || !SkyBlockLocation.zone().isEmpty()) {
            return SkyBlockLocation.onIsland(END_ISLAND);
        }
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level != null && minecraft.level.dimension() == Level.END;
    }
}
