/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.pathfinding;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.dev.DevMode;
import sbs.modid.client.core.render.WorldRender;

import java.util.List;
import java.util.Set;

/**
 * Draws the pathfinding module's waypoints and the route to the nearest one.
 *
 * <p>The route is rendered as a glowing energy conduit with a pulse travelling toward the target
 * (see {@link SciFiPathStyle}); waypoints get a pulsing marker box and a vertical beam so they are
 * findable from a distance and read as a target rather than a debug box.
 *
 * <p>Both layers are independently toggleable, and both are dev-mode gated while the module is being
 * tested – with dev mode off nothing here draws.
 *
 * <p>Rendering goes through {@link WorldRender}, which projects world points to the screen by hand
 * (26.2 has no line-box world renderer – see that class), so colour and line width are ours to set.
 */
public final class PathRenderer {

    /** Line width of the path core, in pixels. */
    private static final int PATH_WIDTH = 2;

    /** How far above the block centre the path line is drawn, so it sits on the floor. */
    private static final double PATH_Y_OFFSET = 0.12;

    /**
     * Waypoints further than this are drawn without their label, to avoid text spam. The marker
     * itself is only distance-culled when its publisher asked for it through
     * {@link Waypoint#maxDistance} - by default it draws at any range.
     *
     * <p>An objective marker gets a much longer leash: with remembered terrain it can legitimately
     * sit a thousand blocks out on another island of the same map, and at that range the label -
     * the name and the distance - is the entire information. There is only ever one of them, so it
     * cannot become the spam this limit exists to prevent.
     *
     * <p>A ping gets the same leash for the same reason. It is placed by hand at up to the ping
     * range - hundreds of blocks - and the distance on its label is most of what it is for, so
     * clipping the label at 96 would hide it exactly when it says the most. Their number is capped
     * by the setting that caps the pings themselves, so a handful is the worst case.
     */
    private static final double LABEL_RANGE = 96.0;
    private static final double LONG_LABEL_RANGE = 2_048.0;

    private static double labelRange(sbs.modid.client.core.pathfinding.Waypoint waypoint) {
        return waypoint.isObjective() || waypoint.isPing() ? LONG_LABEL_RANGE : LABEL_RANGE;
    }

    /** The waypoint beam: how tall, and how many slices its fade is built from. */
    private static final double BEAM_HEIGHT = 3.0;
    private static final int BEAM_SLICES = 6;

    /** One full breath of the waypoint marker. */
    private static final long MARKER_PERIOD_MS = 1_800L;

    /**
     * Below this alpha factor a marker is skipped outright rather than drawn at an alpha that rounds
     * to nothing - which is what makes a near-fade actually stop costing anything once it has faded.
     */
    private static final double MIN_VISIBLE_SCALE = 0.01;

    private PathRenderer() {
    }

    private static SBSConfig.PathfindingSettings cfg() {
        return ConfigManager.getInstance().get().pathfinding;
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        SBSConfig.PathfindingSettings cfg = cfg();
        // Cheapest possible early-out first: with no waypoints stored and no path computed there is
        // nothing to draw whatever the toggles say. It matters because the toggles are now on by
        // default for every player (the map module), and the checks below allocate - this keeps the
        // common "nothing armed" frame free of per-frame garbage.
        if (!WaypointStore.hasAny() && !PathfindingManager.getInstance().hasAnyPath()) {
            return;
        }
        if (!PathRouting.routing() && !PathRouting.drawingWaypoints()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        Camera camera = minecraft.gameRenderer.mainCamera();
        Vec3 camPos = camera.position();
        Matrix4f viewProjection = camera.getViewRotationProjectionMatrix(new Matrix4f());

        Set<Waypoint> marked = Set.of();
        if (PathRouting.routing()) {
            drawPaths(g, viewProjection, camPos, cfg);
            marked = RouteMarkers.draw(g, viewProjection, camPos, cfg, minecraft.font);
        }
        if (PathRouting.drawingWaypoints()) {
            drawWaypoints(g, viewProjection, camPos, cfg, minecraft.font, marked);
        }
    }

    /**
     * Draws an arbitrary world-space polyline in the exact same sci-fi conduit style as the
     * pathfinding route (layered glow + travelling pulse + direction chevrons). Public so other
     * modules with their own ordered points to connect - e.g. Secret Routes' walk trail - reuse this
     * one renderer instead of copying the path look. Points are drawn as given (lift them off the
     * floor beforehand if needed).
     */
    public static void drawWorldLine(GuiGraphicsExtractor g, Matrix4f viewProjection, Vec3 camPos,
                                     List<Vec3> points, int rgb, int width) {
        if (points.size() < 2) {
            return;
        }
        int gw = g.guiWidth();
        int gh = g.guiHeight();
        double phase = SciFiPathStyle.pulsePhase();
        int segments = points.size() - 1;
        int[] previous = WorldRender.projectToScreen(viewProjection, camPos, points.get(0), gw, gh);
        for (int i = 1; i < points.size(); i++) {
            int[] current = WorldRender.projectToScreen(viewProjection, camPos, points.get(i), gw, gh);
            if (previous != null && current != null) {
                double t = (double) (i - 1) / segments;
                SciFiPathStyle.segment(g, previous[0], previous[1], current[0], current[1],
                        t, phase, rgb, Math.max(1, width));
                if (SciFiPathStyle.isChevronAt(i)) {
                    SciFiPathStyle.chevron(g, previous[0], previous[1], current[0], current[1],
                            rgb, Math.max(1, width));
                }
            }
            previous = current;
        }
    }

    // ------------------------------------------------------------------
    // Route
    // ------------------------------------------------------------------

    /**
     * Every route, each in its source's colour. Secondaries first, lowest priority first, the primary
     * last so it lies on top where routes share a corridor. Each secondary is lifted a little higher
     * than the last and drawn dimmer and thinner, so two routes along the same blocks read as two
     * lines side by side instead of one line flickering between colours.
     */
    private static void drawPaths(GuiGraphicsExtractor g, Matrix4f viewProjection, Vec3 camPos,
                                  SBSConfig.PathfindingSettings cfg) {
        PathfindingManager manager = PathfindingManager.getInstance();
        List<Route> routes = manager.routes();
        Route primary = manager.primary();
        int rank = 0;
        for (int i = routes.size() - 1; i >= 0; i--) {
            Route route = routes.get(i);
            if (route == primary || route.paused() || route.path().size() < 2) {
                continue;
            }
            rank++;
            drawRoute(g, viewProjection, camPos, cfg, route, dim(routeRgb(cfg, route.source())),
                    PATH_WIDTH, SECONDARY_LIFT * rank, SECONDARY_CUBE_SCALE);
        }
        if (primary != null && primary.path().size() >= 2) {
            int rgb = routeRgb(cfg, primary.source());
            drawRoute(g, viewProjection, camPos, cfg, primary, primary.stale() ? dim(rgb) : rgb,
                    PATH_WIDTH + 1, 0.0, 1.0);
        }
    }

    /** One route, in whichever style is configured. */
    private static void drawRoute(GuiGraphicsExtractor g, Matrix4f viewProjection, Vec3 camPos,
                                  SBSConfig.PathfindingSettings cfg, Route route, int rgb, int width,
                                  double lift, double cubeScale) {
        List<BlockPos> path = route.path();
        // Line first, cubes on top: that way the cubes cover the line's corner joins rather than
        // being buried under them when both are on.
        if (cfg.pathStyle.drawsLine()) {
            drawLinePath(g, viewProjection, camPos, route, path, rgb, width, lift);
        }
        if (cfg.pathStyle.drawsCubes()) {
            drawCubePath(g, viewProjection, camPos, cfg, route, path, rgb, lift, cubeScale);
        }
        drawPadHints(g, viewProjection, camPos, Minecraft.getInstance().font, route, path, rgb, lift);
        drawSneakHints(g, viewProjection, camPos, Minecraft.getInstance().font, route, path, lift);
    }

    /**
     * "Sneak" over the first node of every run of sneak nodes - where the player has to crouch.
     * Sneaking also keeps them from walking off an edge; the route does not depend on that.
     */
    private static void drawSneakHints(GuiGraphicsExtractor g, Matrix4f viewProjection, Vec3 camPos,
                                       Font font, Route route, List<BlockPos> path, double lift) {
        Set<BlockPos> sneaks = route.sneakNodes();
        if (sneaks.isEmpty()) {
            return;
        }
        for (int i = 0; i < path.size(); i++) {
            if (!sneaks.contains(path.get(i)) || (i > 0 && sneaks.contains(path.get(i - 1)))) {
                continue;
            }
            int[] screen = WorldRender.projectToScreen(viewProjection, camPos,
                    centre(path.get(i)).add(0, 1.2 + lift * 4, 0), g.guiWidth(), g.guiHeight());
            if (screen != null) {
                g.centeredText(font, Component.literal("Sneak"), screen[0], screen[1],
                        SciFiPathStyle.argb(SciFiPathStyle.SNEAK_RGB, 255));
            }
        }
    }

    /** How much higher each further secondary route is drawn, in blocks. */
    private static final double SECONDARY_LIFT = 0.07;

    /** Secondary routes' cubes, relative to the primary's. */
    private static final double SECONDARY_CUBE_SCALE = 0.7;

    /**
     * A source's colour. The dev waypoints keep the old shared Path Color until the player picks one
     * for them, so the dev page's colour row still does what it says.
     */
    public static int routeRgb(SBSConfig.PathfindingSettings cfg, RouteSource source) {
        if (source == RouteSource.DEV && (cfg.routeColorHex == null
                || sbs.modid.client.core.render.OverlayColor.parseHex(
                        cfg.routeColorHex.get(source.id())) == null)) {
            return cfg.pathRgb();
        }
        return cfg.routeRgb(source);
    }

    /** A secondary's colour: the source's own, pulled a third of the way toward black. */
    static int dim(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int gr = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return ((r * 2 / 3) << 16) | ((gr * 2 / 3) << 8) | (b * 2 / 3);
    }

    /**
     * A trail of small glowing cubes with the pulse running through them.
     *
     * <p>Only every {@code cubeSpacing}-th node gets one: a cube per block is both visually noisy and
     * needlessly expensive, since each cube costs twelve projected edges.
     */
    private static void drawCubePath(GuiGraphicsExtractor g, Matrix4f viewProjection, Vec3 camPos,
                                     SBSConfig.PathfindingSettings cfg, Route route, List<BlockPos> path,
                                     int rgb, double lift, double cubeScale) {
        double phase = SciFiPathStyle.pulsePhase();
        double size = Math.max(0.05, cfg.cubeSize / 100.0 * cubeScale);
        int spacing = Math.max(1, cfg.cubeSpacing);
        int last = path.size() - 1;
        Set<BlockPos> hops = route.teleportNodes();

        for (int i = 0; i < path.size(); i++) {
            // Always mark the final node, so the route visibly ends AT the waypoint rather than
            // wherever the spacing happened to land. Both ends of a teleport get the same treatment:
            // a jump the spacing skipped would leave the player with no cube to aim the ability from.
            if (i % spacing != 0 && i != last && !isHopEnd(path, hops, i)) {
                continue;
            }
            double t = (double) i / last;
            SciFiPathStyle.cube(g, viewProjection, camPos, cubeCentre(path.get(i), size).add(0, lift, 0),
                    size, t, phase, route.sneakNodes().contains(path.get(i)) ? SciFiPathStyle.SNEAK_RGB : rgb);
        }
    }

    /** The route as a layered, pulsing conduit with periodic direction chevrons. */
    private static void drawLinePath(GuiGraphicsExtractor g, Matrix4f viewProjection, Vec3 camPos,
                                     Route route, List<BlockPos> path, int rgb, int width, double lift) {
        int gw = g.guiWidth();
        int gh = g.guiHeight();
        double phase = SciFiPathStyle.pulsePhase();
        int segments = path.size() - 1;
        Set<BlockPos> hops = route.teleportNodes();
        Set<BlockPos> pads = route.padNodes();
        Set<BlockPos> sneaks = route.sneakNodes();

        // Project once per node, not once per layer - the projection is the expensive part.
        int[] previous = WorldRender.projectToScreen(viewProjection, camPos,
                centre(path.get(0)).add(0, lift, 0), gw, gh);
        for (int i = 1; i < path.size(); i++) {
            int[] current = WorldRender.projectToScreen(viewProjection, camPos,
                    centre(path.get(i)).add(0, lift, 0), gw, gh);
            if (previous != null && current != null) {
                double t = (double) (i - 1) / segments;
                // A node the route teleports into is the far end of a jump, so the segment leading
                // into it is one the player never walks - see SciFiPathStyle.hop.
                if (pads.contains(path.get(i))) {
                    // A jump pad's flight: an arc from the pad to the landing, never walked.
                    drawPadArc(g, viewProjection, camPos, path.get(i - 1), path.get(i), t, phase, rgb,
                            width, lift);
                } else if (hops.contains(path.get(i))) {
                    SciFiPathStyle.hop(g, previous[0], previous[1], current[0], current[1],
                            t, phase, rgb, width);
                } else if (sneaks.contains(path.get(i)) || sneaks.contains(path.get(i - 1))) {
                    // In, through or out of a 1.5-block gap: the player has to be crouched.
                    SciFiPathStyle.segment(g, previous[0], previous[1], current[0], current[1],
                            t, phase, SciFiPathStyle.SNEAK_RGB, width);
                } else {
                    SciFiPathStyle.segment(g, previous[0], previous[1], current[0], current[1],
                            t, phase, rgb, width);
                }
                if (SciFiPathStyle.isChevronAt(i)) {
                    SciFiPathStyle.chevron(g, previous[0], previous[1], current[0], current[1],
                            rgb, width);
                }
            }
            previous = current;
        }
    }

    /**
     * A jump pad's flight as a dashed parabola from the pad to the landing. The apex rises a quarter
     * of the flight's length (at least three blocks) - a picture of "you will be thrown", not a
     * prediction of the real trajectory, which the pad data does not record.
     */
    private static void drawPadArc(GuiGraphicsExtractor g, Matrix4f viewProjection, Vec3 camPos,
                                   BlockPos from, BlockPos to, double t, double phase, int rgb,
                                   int width, double lift) {
        Vec3 a = centre(from).add(0, lift, 0);
        Vec3 b = centre(to).add(0, lift, 0);
        double apex = Math.max(3.0, a.distanceTo(b) / 4.0);
        int gw = g.guiWidth();
        int gh = g.guiHeight();
        int[] previous = WorldRender.projectToScreen(viewProjection, camPos, a, gw, gh);
        for (int k = 1; k <= PAD_ARC_STEPS; k++) {
            double u = (double) k / PAD_ARC_STEPS;
            Vec3 point = a.lerp(b, u).add(0, apex * 4 * u * (1 - u), 0);
            int[] current = WorldRender.projectToScreen(viewProjection, camPos, point, gw, gh);
            if (previous != null && current != null) {
                SciFiPathStyle.hop(g, previous[0], previous[1], current[0], current[1], t, phase, rgb,
                        width);
            }
            previous = current;
        }
    }

    /** "Step on the jump pad" over every pad the route uses. The player walks on; nothing is pressed. */
    private static void drawPadHints(GuiGraphicsExtractor g, Matrix4f viewProjection, Vec3 camPos,
                                     Font font, Route route, List<BlockPos> path, int rgb, double lift) {
        Set<BlockPos> pads = route.padNodes();
        if (pads.isEmpty()) {
            return;
        }
        for (int i = 1; i < path.size(); i++) {
            if (!pads.contains(path.get(i))) {
                continue;
            }
            int[] screen = WorldRender.projectToScreen(viewProjection, camPos,
                    centre(path.get(i - 1)).add(0, 1.6 + lift * 4, 0), g.guiWidth(), g.guiHeight());
            if (screen != null) {
                g.centeredText(font, Component.literal("Step on the jump pad"), screen[0], screen[1],
                        SciFiPathStyle.argb(rgb, 255));
            }
        }
    }

    /** Points per pad arc: enough for a smooth curve, few enough to stay cheap per frame. */
    private static final int PAD_ARC_STEPS = 16;

    /** Whether node {@code i} is either end of a teleport hop. */
    private static boolean isHopEnd(List<BlockPos> path, Set<BlockPos> hops, int i) {
        return hops.contains(path.get(i))
                || (i + 1 < path.size() && hops.contains(path.get(i + 1)));
    }

    // ------------------------------------------------------------------
    // Waypoints
    // ------------------------------------------------------------------

    /** A pulsing marker and a light beam on every waypoint; the routed one is emphasised. */
    private static void drawWaypoints(GuiGraphicsExtractor g, Matrix4f viewProjection, Vec3 camPos,
                                      SBSConfig.PathfindingSettings cfg, Font font,
                                      Set<Waypoint> marked) {
        Waypoint routed = PathfindingManager.getInstance().target();
        // The global preset is the fallback, not the answer: a waypoint carrying its own colour wins,
        // so one publisher can draw two sets in two colours (honey trees against hives) without the
        // renderer knowing what either is. Resolved per waypoint rather than hoisted, which is the
        // whole change - it used to be one colour for every marker in the world.
        int preset = cfg.waypointRgb();
        double breath = breath();

        for (Waypoint waypoint : PathRouting.visibleWaypoints()) {
            BlockPos pos = waypoint.pos();
            boolean active = waypoint == routed;
            int rgb = waypoint.rgb(preset);

            // Distance first, because it decides whether this marker is drawn at all: a set can cap
            // its own range, and a waypoint asking to fade near the player is invisible inside its
            // own fade radius. Finding either out before the occlusion ray means the ray is never
            // cast for a marker nobody can see. The two are independent - a capped set still fades
            // up close, and everything a waypoint says nothing about scales by 1.0 and is unchanged.
            double distance = camPos.distanceTo(centre(pos));
            if (waypoint.maxDistance > 0 && distance > waypoint.maxDistance) {
                continue;   // the publisher capped this set's range; out of range is not drawn at all
            }
            double alphaScale = waypoint.alphaScale(distance);
            if (alphaScale < MIN_VISIBLE_SCALE) {
                continue;
            }
            if (!waypoint.throughWalls && occluded(camPos, centre(pos).add(0, 0.5, 0))) {
                continue;   // the caller asked for this one to hide behind terrain
            }
            drawMarker(g, viewProjection, camPos, pos, rgb, active, breath, alphaScale);
            drawBeam(g, viewProjection, camPos, pos, rgb, active, alphaScale);

            // A route's destination marker already carries this waypoint's name and distance;
            // a second label on the same spot would just be the same words twice.
            if (distance <= labelRange(waypoint) && !marked.contains(waypoint)) {
                drawLabel(g, viewProjection, camPos, font, waypoint, pos, rgb, active, distance,
                        alphaScale);
            }
        }
    }

    /**
     * An alpha scaled by a waypoint's opacity and near-fade, never below zero.
     *
     * <p>Rounded rather than truncated so a percentage round-trips to the alpha it came from - 52 %
     * of 255 is 132.6, and the requested value is 133.
     */
    private static int scaled(int alpha, double scale) {
        return Math.max(0, (int) Math.round(alpha * scale));
    }

    /** The marker box: a bloom shell plus a crisp core that breathes. */
    private static void drawMarker(GuiGraphicsExtractor g, Matrix4f viewProjection, Vec3 camPos,
                                   BlockPos pos, int rgb, boolean active, double breath,
                                   double alphaScale) {
        // The active waypoint breathes; the others sit still so the target stands out.
        double grow = active ? 0.04 + 0.05 * breath : 0.02;
        int coreAlpha = active ? (int) (170 + 85 * breath) : 130;

        box(g, viewProjection, camPos, pos, grow + 0.05,
                SciFiPathStyle.argb(rgb, scaled(40, alphaScale)), active ? 4 : 3);
        box(g, viewProjection, camPos, pos, grow,
                SciFiPathStyle.argb(rgb, scaled(coreAlpha, alphaScale)), active ? 2 : 1);
    }

    /** Draws a cube around a block, inflated by {@code grow} on every side. */
    private static void box(GuiGraphicsExtractor g, Matrix4f viewProjection, Vec3 camPos,
                            BlockPos pos, double grow, int color, int width) {
        WorldRender.boxEdges(g, viewProjection, camPos,
                pos.getX() - grow, pos.getY() - grow, pos.getZ() - grow,
                pos.getX() + 1 + grow, pos.getY() + 1 + grow, pos.getZ() + 1 + grow,
                color, width);
    }

    /**
     * A vertical light beam above the waypoint, built from stacked slices whose alpha falls off with
     * height. That is what makes a waypoint findable across a room without hunting for a small box.
     */
    private static void drawBeam(GuiGraphicsExtractor g, Matrix4f viewProjection, Vec3 camPos,
                                 BlockPos pos, int rgb, boolean active, double alphaScale) {
        double x = pos.getX() + 0.5;
        double z = pos.getZ() + 0.5;
        double baseY = pos.getY() + 1.0;
        int gw = g.guiWidth();
        int gh = g.guiHeight();
        int topAlpha = active ? 120 : 60;

        int[] previous = WorldRender.projectToScreen(viewProjection, camPos,
                new Vec3(x, baseY, z), gw, gh);
        for (int i = 1; i <= BEAM_SLICES; i++) {
            double y = baseY + BEAM_HEIGHT * i / BEAM_SLICES;
            int[] current = WorldRender.projectToScreen(viewProjection, camPos, new Vec3(x, y, z), gw, gh);
            if (previous != null && current != null) {
                int alpha = (int) (topAlpha * (1.0 - (double) i / BEAM_SLICES));
                WorldRender.line(g, previous[0], previous[1], current[0], current[1],
                        SciFiPathStyle.argb(rgb, scaled(alpha, alphaScale)), active ? 3 : 2);
            }
            previous = current;
        }
    }

    private static void drawLabel(GuiGraphicsExtractor g, Matrix4f viewProjection, Vec3 camPos,
                                  Font font, Waypoint waypoint, BlockPos pos, int rgb,
                                  boolean active, double distance, double alphaScale) {
        int[] screen = WorldRender.projectToScreen(viewProjection, camPos,
                centre(pos).add(0, BEAM_HEIGHT + 0.4, 0), g.guiWidth(), g.guiHeight());
        if (screen == null) {
            return;
        }
        String label = waypoint.showDistance
                ? waypoint.name + " §7" + (int) Math.round(distance) + "m"
                : waypoint.name;
        PathfindingManager manager = PathfindingManager.getInstance();
        if (active && manager.noRoute() && !manager.searching()) {
            // Another island with no known pad: no line at all, and the reason where the eye is.
            label += " §c(no route to that island known)";
        } else if (active && !manager.reachedGoal() && !manager.searching()) {
            // Be honest when the route only gets close rather than all the way there.
            label += " §c(partial)";
        }
        g.centeredText(font, Component.literal(label), screen[0], screen[1],
                SciFiPathStyle.argb(rgb, scaled(255, alphaScale)));

        // A second line under the name, for state that belongs to the marker but is not its name.
        // Drawn at the same alpha so it fades with the marker, and in its own colour when it has
        // one - the honey timer's amber/green is the readout, not decoration.
        String sub = waypoint.subLabel;
        if (sub != null && !sub.isEmpty()) {
            Integer own = sbs.modid.client.core.render.OverlayColor
                    .parseHex(waypoint.subLabelColorHex);
            int subRgb = own == null ? rgb : own;
            g.centeredText(font, Component.literal(sub), screen[0], screen[1] + font.lineHeight,
                    SciFiPathStyle.argb(subRgb, scaled(255, alphaScale)));
        }
    }

    /**
     * Whether a solid block sits between the camera and {@code target} – the depth test this
     * renderer does not get for free.
     *
     * <p>The world overlay projects world points onto the HUD, so nothing here is ever hidden by
     * terrain on its own; a waypoint that asked not to be drawn through walls has to be tested by
     * hand. Only called for those, so the ray costs nothing for everything else.
     */
    static boolean occluded(Vec3 camPos, Vec3 target) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return false;
        }
        HitResult hit = minecraft.level.clip(new ClipContext(camPos, target,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, minecraft.player));
        return hit.getType() == HitResult.Type.BLOCK
                && hit.getLocation().distanceToSqr(camPos) < target.distanceToSqr(camPos) - 1.0;
    }

    /** A 0..1 triangle wave driving the marker pulse (long modulo first – see SciFiPathStyle). */
    private static double breath() {
        double t = (System.currentTimeMillis() % MARKER_PERIOD_MS) / (double) MARKER_PERIOD_MS;
        return t < 0.5 ? t * 2 : (1 - t) * 2;
    }

    /** The centre of a block, lifted slightly so a path line rests on the floor. */
    private static Vec3 centre(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5, pos.getY() + PATH_Y_OFFSET, pos.getZ() + 0.5);
    }

    /** A cube's centre: raised by its own half-height so it sits ON the floor, not sunk into it. */
    private static Vec3 cubeCentre(BlockPos pos, double size) {
        return new Vec3(pos.getX() + 0.5, pos.getY() + PATH_Y_OFFSET + size / 2.0, pos.getZ() + 0.5);
    }
}
