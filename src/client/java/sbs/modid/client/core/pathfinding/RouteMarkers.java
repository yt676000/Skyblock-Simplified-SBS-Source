/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.pathfinding;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.render.WorldRender;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * A beacon-style pillar over each route's destination, with the source, the target and the route
 * distance on it ("Fairy Soul · 42m", "Bartender · 18m").
 *
 * <p>The marker belongs to the route, not to a waypoint: it stands on the goal the route leads to -
 * for a candidate set, the one the search picked - so it moves when the pick changes and disappears
 * with the route. Drawn for every running route, including stale ones during their grace, which is
 * what keeps a flickering objective's marker still on screen.
 */
final class RouteMarkers {

    /** Hidden when the player is this close: they are there. */
    private static final double HIDE_WITHIN = 4.0;

    /** The pillar: tall enough to find over a hill, sliced for its fade. */
    private static final double PILLAR_HEIGHT = 24.0;
    private static final int PILLAR_SLICES = 8;

    /** Where the label sits above the goal block, in blocks. */
    private static final double LABEL_HEIGHT = 3.4;

    private RouteMarkers() {
    }

    /**
     * Draws every route's marker and returns the waypoints that got one, so the waypoint layer can
     * leave out its own label for them.
     */
    static Set<Waypoint> draw(GuiGraphicsExtractor g, Matrix4f viewProjection, Vec3 camPos,
                              SBSConfig.PathfindingSettings cfg, Font font) {
        List<Route> routes = PathfindingManager.getInstance().routes();
        if (routes.isEmpty()) {
            return Set.of();
        }
        Route primary = PathfindingManager.getInstance().primary();
        Set<Waypoint> marked = Collections.newSetFromMap(new IdentityHashMap<>());
        // Lowest priority first, so the primary's label is drawn last and lands on top.
        for (int i = routes.size() - 1; i >= 0; i--) {
            Route route = routes.get(i);
            Waypoint target = route.target();
            if (route.paused() || target == null || !cfg.routeMarker(route.source())) {
                continue;
            }
            BlockPos pos = target.pos();
            Vec3 base = new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            double distance = camPos.distanceTo(base);
            marked.add(target);
            if (distance < HIDE_WITHIN) {
                continue;
            }
            if (!cfg.routeMarkersThroughWalls && PathRenderer.occluded(camPos, base.add(0, 1.0, 0))) {
                continue;
            }
            boolean isPrimary = route == primary;
            int rgb = PathRenderer.routeRgb(cfg, route.source());
            if (route.stale()) {
                rgb = PathRenderer.dim(rgb);   // still there, visibly on borrowed time
            }
            drawPillar(g, viewProjection, camPos, base, rgb, distance, isPrimary, route.stale());
            drawLabel(g, viewProjection, camPos, font, route, target, base, rgb, distance);
        }
        return marked;
    }

    /**
     * A vertical beam with a fading top. Its width grows with distance so it stays a findable stripe
     * across an island instead of shrinking to a hairline - the projection makes far things small,
     * and this is the one thing that should not get smaller.
     */
    private static void drawPillar(GuiGraphicsExtractor g, Matrix4f viewProjection, Vec3 camPos,
                                   Vec3 base, int rgb, double distance, boolean primary, boolean stale) {
        int width = (int) Math.max(2, Math.min(6, 2 + distance / 48.0)) + (primary ? 1 : 0);
        int bottomAlpha = stale ? 70 : primary ? 170 : 120;
        int gw = g.guiWidth();
        int gh = g.guiHeight();
        int[] previous = WorldRender.projectToScreen(viewProjection, camPos, base, gw, gh);
        for (int i = 1; i <= PILLAR_SLICES; i++) {
            double y = PILLAR_HEIGHT * i / PILLAR_SLICES;
            int[] current = WorldRender.projectToScreen(viewProjection, camPos, base.add(0, y, 0), gw, gh);
            if (previous != null && current != null) {
                int alpha = (int) (bottomAlpha * (1.0 - (double) (i - 1) / PILLAR_SLICES));
                WorldRender.line(g, previous[0], previous[1], current[0], current[1],
                        SciFiPathStyle.argb(rgb, alpha), width);
            }
            previous = current;
        }
    }

    private static void drawLabel(GuiGraphicsExtractor g, Matrix4f viewProjection, Vec3 camPos, Font font,
                                  Route route, Waypoint target, Vec3 base, int rgb, double distance) {
        int[] screen = WorldRender.projectToScreen(viewProjection, camPos, base.add(0, LABEL_HEIGHT, 0),
                g.guiWidth(), g.guiHeight());
        if (screen == null) {
            return;
        }
        g.centeredText(font, Component.literal(label(route, target, distance)), screen[0], screen[1],
                SciFiPathStyle.argb(rgb, route.stale() ? 150 : 255));
    }

    /**
     * "Bartender · 18m" for a single destination - its own name says more than the source's - and
     * "Fairy Soul · 42m" for a candidate set, whose members are all called the same. The distance is
     * along the route once there is one, straight-line until then.
     */
    static String label(Route route, Waypoint target, double straightLine) {
        String name = route.goals().size() > 1 || target.name == null || target.name.isBlank()
                ? route.source().displayName() : target.name;
        double metres = route.path().size() >= 2 && route.reachedGoal() ? route.length() : straightLine;
        String text = name + " · " + Math.round(metres) + "m";
        return switch (route.state()) {
            case NO_ROUTE -> text + (route.deepExhausted()
                    ? " §c(no walkable route - may need a hidden entrance or a jump the planner can't do)"
                    : " §c(no route known)");
            case PARTIAL -> text + " §c(partial)";
            case SEARCHING -> text + " §7…";
            case DEEP_SEARCH -> text + " §7Searching the whole island... "
                    + Math.round(route.deepProgress() * 100) + "%";
            default -> route.unverified() ? text + " §e(unverified)" : text;
        };
    }
}
