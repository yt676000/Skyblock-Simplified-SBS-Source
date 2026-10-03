/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.garden.render;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.render.WorldRender;
import sbs.modid.client.skills.garden.logic.GardenBlueprintManager;
import sbs.modid.client.skills.garden.logic.PestTracker;
import sbs.modid.client.skills.garden.model.GardenPlot;
import sbs.modid.client.skills.garden.model.GardenPlotCatalog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pest highlight + infested-plot highlight for the Garden.
 *
 * <p><b>highlight</b> boxes the actual pest entities, but only while they are on screen (in the camera
 * frustum) – the user's ask, and what makes it read as "highlight what I can see" rather than a
 * wall-hack. <b>Plot highlight</b> outlines the 96×96 plot cell each detected pest sits in, so a plot
 * with pests is obvious from across the Garden. Deriving the plot from the pest's
 * own position means it is always the right plot – no fragile "Plot N → world location" table.
 *
 * <p><b>Detection is positive, by nametag.</b> Garden pests are ordinary mobs Hypixel dresses up,
 * not their own entity type - but every one of them carries the pest glyph ({@code ൠ}) in its
 * floating nametag, and their species names are a fixed, small catalog. So a mob only counts when
 * its tag (own name, or the armor-stand tag above it) says so. The old rule - "anything that is not
 * a villager is a pest" - boxed the player's own Witherborn minions and a witch NPC the moment they
 * stood in the Garden; a catalog cannot be fooled by whatever else happens to walk past. The
 * throttled {@code [SBS][Pest]} log now dumps the nearby nametags whenever the widget says pests are
 * alive but none matched, so a renamed pest can be re-pinned from the log alone.
 */
public final class PestHighlight {

    /** How far to look for pest mobs each frame. */
    private static final double SCAN_RADIUS = 40.0;
    private static final long LOG_INTERVAL_MS = 5_000L;

    private static long lastLogAt;
    /** Throttle for the render-gate diagnostic. */
    private static long lastGateLogAt;

    private PestHighlight() {
    }

    private static SBSConfig.GardenSettings cfg() {
        return ConfigManager.getInstance().get().garden;
    }

    /** Throttles the mapping-mismatch warning. */
    private static long lastMappingLogAt;

    /** Throttle for the drawn-plots confirmation log. */
    private static long lastDrawLogAt;

    /**
     * Outlines every plot known to be infested - all of them at once, from anywhere on the Garden.
     *
     * <p><b>The tab widget is THE source, unconditionally.</b> Its "Plots:" list is complete - it
     * names every infested plot including the ones whose pests are not loaded - and its numbers map
     * straight to world cells through Hypixel's fixed layout ({@link GardenPlotCatalog#cellOf}), so
     * no pest ever has to be detected for the highlight to work. This used to sit behind a
     * "Use Tab Widget" toggle, a leftover from when the mapping needed the Configure Plots menu and
     * a rotation guess; with that toggle off the highlight silently degraded to "only the plot whose
     * pests are loaded around you", which reads as broken. There is no correct off position, so the
     * toggle is gone.
     *
     * <p>Pest positions still run as an additive second source: they catch a fresh spawn the widget
     * has not caught up with yet, and they are the ground truth the mapping is checked against.
     */
    private static void drawInfestedPlots(GuiGraphicsExtractor g, SBSConfig.GardenSettings cfg,
                                          Matrix4f viewProjection, Vec3 camPos,
                                          List<Entity> pests, double ground, int plotColor) {
        Set<Long> drawn = new HashSet<>();
        Set<Integer> infested = PestTracker.getInstance().infestedPlots();
        for (int number : infested) {
            int[] cell = GardenPlotCatalog.cellOf(number);
            if (cell == null) {
                continue;   // not a number of the fixed layout - a misparse, not a plot
            }
            GardenPlot.Bounds plot = new GardenPlot.Bounds(cell[0], cell[1],
                    GardenPlot.minCorner(cell[0]), GardenPlot.minCorner(cell[1]));
            if (drawn.add(cellKey(plot))) {
                drawPlot(g, cfg, viewProjection, camPos, plot, ground, plotColor);
            }
        }
        warnOnMappingMismatch(pests);
        for (Entity pest : pests) {
            GardenPlot.Bounds plot = GardenPlot.at(pest.getX(), pest.getZ());
            // Same bedrock floor for every plot - a pest standing on a slab must not lift its border.
            if (drawn.add(cellKey(plot))) {
                drawPlot(g, cfg, viewProjection, camPos, plot, ground, plotColor);
            }
        }
        // Proof-of-life for live tuning: says WHAT is drawn and from WHERE, so "the highlight does
        // not work" can be split into widget-not-parsed vs mapping-wrong vs render-gate in one look.
        long now = System.currentTimeMillis();
        if (!drawn.isEmpty() && now - lastDrawLogAt > 10_000L) {
            lastDrawLogAt = now;
            SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][Pest] highlighting {} plot(s): widget={} + {} pest position(s)",
                    drawn.size(), infested, pests.size());
        }
    }

    private static long cellKey(GardenPlot.Bounds plot) {
        return ((long) plot.cellX() << 32) ^ (plot.cellZ() & 0xFFFFFFFFL);
    }

    /** The Y the plot outlines stand on, once resolved. {@code NaN} until the first sample. */
    private static double groundY = Double.NaN;
    /** True once {@link #groundY} is a real bedrock hit - final for this world, never resampled. */
    private static boolean groundFromBedrock;
    /** Identity of the level {@link #groundY} was resolved in - a warp invalidates it. */
    private static java.lang.ref.WeakReference<Object> groundWorld =
            new java.lang.ref.WeakReference<>(null);
    /** When the last (non-bedrock) sample was taken - retrying is fine, but not every frame. */
    private static long groundSampledAt;
    private static final long GROUND_REFRESH_MS = 500L;

    /**
     * The Y the plot outlines stand on: the Garden's <b>bedrock</b> floor.
     *
     * <p>Bedrock is the one height in the Garden that nothing can move: plots are diggable all the
     * way down to it, so any "floor" read off the terrain - and worse, off the player, which this
     * used to do - sinks with every excavated plot and rides along when you stand on a farm
     * structure. The bedrock layer under the island is where every dig ends, which makes it the
     * fixed ground line: outlines anchored there enclose a dug-out plot from its true bottom and
     * never move for any reason. The wall then grows {@code pestPlotWallHeight} blocks up from
     * bedrock (the setting reaches 256, enough to clear the surface by plenty).
     *
     * <p>Resolved by scanning the player's own column downward for the bedrock block - the answer is
     * a world constant, so one hit is cached for the whole session (until a warp swaps the level).
     * Until a scan connects (flying past the island edge, unloaded chunk below), the old
     * surface-based reading stands in so the highlight never blinks out: first solid block under the
     * player, else the last known value, else the player's feet.
     */
    private static double groundLevel(ClientLevel level, LocalPlayer player) {
        if (groundWorld.get() != level) {
            groundWorld = new java.lang.ref.WeakReference<>(level);
            groundY = Double.NaN;
            groundFromBedrock = false;
        }
        if (groundFromBedrock) {
            return groundY;
        }
        long now = System.currentTimeMillis();
        if (!Double.isNaN(groundY) && now - groundSampledAt < GROUND_REFRESH_MS) {
            return groundY;
        }
        groundSampledAt = now;
        int x = (int) Math.floor(player.getX());
        int z = (int) Math.floor(player.getZ());
        int from = (int) Math.floor(player.getY());
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        double solidFallback = Double.NaN;
        for (int y = from; y >= level.getMinY(); y--) {
            var state = level.getBlockState(pos.set(x, y, z));
            if (state.is(net.minecraft.world.level.block.Blocks.BEDROCK)) {
                groundY = y + 1;   // stand the outline on bedrock's top face
                groundFromBedrock = true;
                SkyblockSimplifiedSBS.LOGGER.info(
                        "[SBS][Pest] plot outlines anchored to bedrock at Y {}", (int) groundY);
                return groundY;
            }
            if (Double.isNaN(solidFallback) && !state.isAir()) {
                solidFallback = y + 1;   // remembered in passing; only used when no bedrock shows
            }
        }
        // No bedrock in this column (island edge, void below): stand on what there is, prefer what
        // was known - a stale floor is still far better than one that follows the player.
        if (!Double.isNaN(solidFallback)) {
            groundY = solidFallback;
            return groundY;
        }
        return Double.isNaN(groundY) ? player.getY() : groundY;
    }

    /** Screen-space width of one wall fill strip - see {@link WorldRender#fillQuad}. */
    private static final int WALL_STRIP = 3;

    private static void drawPlot(GuiGraphicsExtractor g, SBSConfig.GardenSettings cfg,
                                 Matrix4f viewProjection, Vec3 camPos, GardenPlot.Bounds plot,
                                 double ground, int plotColor) {
        double x0 = plot.minX();
        double z0 = plot.minZ();
        double x1 = plot.maxX() + 1;
        double z1 = plot.maxZ() + 1;
        // Anchored to the floor and grown upward from it, so the wall is exactly as tall as the
        // setting says regardless of where the player happens to be.
        double bottom = ground;
        double top = bottom + Math.max(1, cfg.pestPlotWallHeight);

        // Walls first, so the edges stay crisp on top of their own tint.
        if (cfg.pestPlotWalls) {
            int wallColor = withOpacity(plotColor, cfg.pestPlotWallOpacity);
            wall(g, viewProjection, camPos, x0, z0, x1, z0, bottom, top, wallColor);
            wall(g, viewProjection, camPos, x1, z0, x1, z1, bottom, top, wallColor);
            wall(g, viewProjection, camPos, x1, z1, x0, z1, bottom, top, wallColor);
            wall(g, viewProjection, camPos, x0, z1, x0, z0, bottom, top, wallColor);
        }
        WorldRender.boxEdges(g, viewProjection, camPos, x0, bottom, z0, x1, top, z1, plotColor, 2);
        // The plot-centre tracer has its own toggle: with several plots infested at once, six lines
        // converging on the crosshair read as clutter, so it is opt-in - the pest tracer toggle
        // stays about pests only.
        if (cfg.pestPlotTracer) {
            // Aim at the middle of the wall rather than at the floor: on a tall outline seen from
            // across the island, a line to the ground point reads as pointing past the plot.
            WorldRender.tracer(g, viewProjection, camPos,
                    new Vec3((x0 + x1) / 2.0, (bottom + top) / 2.0, (z0 + z1) / 2.0), plotColor, 2);
        }
    }

    /** One vertical side of the plot, from its ground line up to the outline's top. */
    private static void wall(GuiGraphicsExtractor g, Matrix4f viewProjection, Vec3 camPos,
                             double ax, double az, double bx, double bz,
                             double bottom, double top, int color) {
        WorldRender.fillQuad(g, viewProjection, camPos,
                new Vec3(ax, bottom, az), new Vec3(bx, bottom, bz),
                new Vec3(bx, top, bz), new Vec3(ax, top, az), color, WALL_STRIP);
    }

    /** Replaces a colour's alpha with the configured 0-100 tint strength. */
    private static int withOpacity(int argb, int percent) {
        int alpha = Math.round(Math.max(0, Math.min(100, percent)) * 255f / 100f);
        return (alpha << 24) | (argb & 0x00FFFFFF);
    }

    /**
     * A visible pest is ground truth: the plot it stands in must be one the widget calls infested.
     * The number→cell table is a hardcoded constant now, so a disagreement means either the table's
     * orientation is off after all or the widget is briefly stale - worth a log line either way,
     * rather than quietly outlining the wrong plots.
     */
    private static void warnOnMappingMismatch(List<Entity> pests) {
        if (pests.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastMappingLogAt < 30_000L) {
            return;
        }
        Set<Integer> infested = PestTracker.getInstance().infestedPlots();
        if (infested.isEmpty()) {
            return;
        }
        for (Entity pest : pests) {
            GardenPlot.Bounds plot = GardenPlot.at(pest.getX(), pest.getZ());
            int number = GardenPlotCatalog.numberAt(plot.cellX(), plot.cellZ());
            if (number > 0 && !infested.contains(number)) {
                lastMappingLogAt = now;
                sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                        "[SBS][Pest] plot table disagrees: pest stands in cell {},{} = plot {}, "
                                + "but the widget lists {}.",
                        plot.cellX(), plot.cellZ(), number, infested);
                return;
            }
        }
    }

    /** Called from the HUD world-render pass once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        SBSConfig.GardenSettings cfg = cfg();
        if (!cfg.pestHighlight && !cfg.pestPlotHighlight) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null) {
            return;
        }
        // Two independent proofs of being on the Garden, either is enough. The location service
        // reads the tab Area line / the scoreboard zone; the Pests widget is only ever published ON
        // the Garden, so a freshly parsed widget is proof by itself. The second saved this feature
        // once already: a regression in the location layer silently killed the whole renderer, and
        // a gate with one leg has exactly that failure mode. The log says which legs held.
        boolean locationSaysGarden = GardenBlueprintManager.inGarden();
        boolean widgetSaysGarden = PestTracker.getInstance().onGarden();
        if (!locationSaysGarden && !widgetSaysGarden) {
            long now = System.currentTimeMillis();
            if (now - lastGateLogAt > 10_000L) {
                lastGateLogAt = now;
                SkyblockSimplifiedSBS.LOGGER.info(
                        "[SBS][Pest] not rendering: location gate says '{}', pests widget not seen",
                        sbs.modid.client.core.location.SkyBlockLocation.describe());
            }
            return;
        }

        List<Entity> pests = collectPests(level, player);
        // No early return on an empty list any more: the tab widget knows about infested plots whose
        // pests are nowhere near loaded, and those are exactly the ones worth outlining.
        if (pests.isEmpty() && !cfg.pestPlotHighlight) {
            return;
        }

        Camera camera = minecraft.gameRenderer.mainCamera();
        Vec3 camPos = camera.position();
        Matrix4f viewProjection = camera.getViewRotationProjectionMatrix(new Matrix4f());
        int highlightColor = cfg.pestHighlightColor.argb();
        int plotColor = cfg.pestPlotColor.argb();

        if (cfg.pestPlotHighlight) {
            drawInfestedPlots(g, cfg, viewProjection, camPos, pests,
                    groundLevel(level, player), plotColor);
        }

        // highlight: box each pest, but only the ones currently on screen.
        if (cfg.pestHighlight) {
            for (Entity pest : pests) {
                AABB box = pest.getBoundingBox();
                if (!onScreen(viewProjection, camPos, box.getCenter())) {
                    continue;
                }
                WorldRender.boxEdges(g, viewProjection, camPos,
                        box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, highlightColor, 2);
                if (cfg.pestHighlightTracer) {
                    WorldRender.tracerToBox(g, viewProjection, camPos, box, highlightColor, 2);
                }
            }
        }
    }

    /** The glyph Hypixel puts in every pest nametag. */
    private static final String PEST_MARK = "ൠ";

    /**
     * The pest species, as whole words. The word boundary is what keeps this from matching past the
     * catalog ("Firefly" does not contain the word "fly"); pet tags are excluded separately because
     * the Rat PET really does carry the word "Rat".
     */
    private static final java.util.regex.Pattern PEST_NAME = java.util.regex.Pattern.compile(
            "(?i)\\b(beetle|cricket|earthworm|fly|locust|mite|mosquito|moth|rat|slug)\\b");

    /** Every pest mob near the player, identified by nametag (+ the tuning log when none match). */
    private static List<Entity> collectPests(ClientLevel level, LocalPlayer player) {
        AABB area = player.getBoundingBox().inflate(SCAN_RADIUS);
        List<Entity> pests = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        List<String> tags = new ArrayList<>();

        // The tag is usually a separate armor stand hovering over the mob - the same stand→mob
        // association every other SBS highlighter uses.
        for (net.minecraft.world.entity.decoration.ArmorStand stand : level.getEntitiesOfClass(
                net.minecraft.world.entity.decoration.ArmorStand.class, area,
                s -> s.hasCustomName())) {
            String name = plainName(stand);
            tags.add(name);
            if (!isPestTag(name)) {
                continue;
            }
            var target = sbs.modid.client.combat.mobhighlight.logic.MobHighlightTracker
                    .mobBelow(level, stand);
            if (target != null && target.isAlive() && seen.add(target.getId())) {
                pests.add(target);
            }
        }
        // Some mobs carry the tag on themselves rather than on a stand.
        for (Mob mob : level.getEntitiesOfClass(Mob.class, area,
                m -> m.isAlive() && m.hasCustomName())) {
            if (isPestTag(plainName(mob)) && seen.add(mob.getId())) {
                pests.add(mob);
            }
        }

        // Tuning aid, throttled: the widget says pests are alive, none matched here - dump the tags
        // that WERE around, so a reworded pest name can be added to the catalog from the log alone.
        if (pests.isEmpty() && PestTracker.getInstance().pestDataFresh()
                && !PestTracker.getInstance().infestedPlots().isEmpty()
                && System.currentTimeMillis() - lastLogAt > LOG_INTERVAL_MS) {
            lastLogAt = System.currentTimeMillis();
            SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][Pest] widget lists infested plots but no nametag matched; nearby tags: {}", tags);
        }
        return pests;
    }

    /** A nametag with the colour codes stripped, for matching. */
    private static String plainName(Entity entity) {
        var name = entity.getCustomName();
        return name == null ? "" : name.getString().replaceAll("§.", "");
    }

    /**
     * Whether a nametag is a pest's: the pest glyph is definitive; the species catalog is the
     * fallback for when the glyph does not survive the font/encoding. Pet tags ({@code [Lv100] Rat})
     * are excluded outright - a pet level prefix can never be on a pest.
     */
    private static boolean isPestTag(String name) {
        if (name.isEmpty() || name.toLowerCase(Locale.ROOT).contains("[lv")) {
            return false;
        }
        return name.contains(PEST_MARK) || PEST_NAME.matcher(name).find();
    }

    /** Whether a world point projects inside the viewport (clip space [-1,1], in front of the camera). */
    private static boolean onScreen(Matrix4f viewProjection, Vec3 camPos, Vec3 point) {
        Vector4f clip = viewProjection.transform(new Vector4f(
                (float) (point.x - camPos.x),
                (float) (point.y - camPos.y),
                (float) (point.z - camPos.z), 1.0f));
        if (clip.w <= 1.0e-4f) {
            return false; // behind the camera
        }
        float ndcX = clip.x / clip.w;
        float ndcY = clip.y / clip.w;
        return ndcX >= -1.0f && ndcX <= 1.0f && ndcY >= -1.0f && ndcY <= 1.0f;
    }

}
