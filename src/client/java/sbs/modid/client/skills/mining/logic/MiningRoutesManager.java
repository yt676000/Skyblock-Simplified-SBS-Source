/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.logic;

import com.google.gson.Gson;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import sbs.modid.client.social.chat.logic.SBSChat;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.config.SBSConfig.MiningRoute;
import sbs.modid.client.core.render.OverlayColor;
import sbs.modid.client.core.render.WorldRender;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * The Mining Routes module: custom routes - ordered waypoints joined by lines,
 * drawn in the world. Routes are created by dropping waypoints at your feet, edited in
 * {@code MiningRoutesScreen}, and shared as a compact base64 string over the clipboard.
 *
 * <p>Rendering mirrors {@code DungeonHighlight} / {@code MobHighlightRenderer}: no world-render mixin exists in
 * 26.2, so each waypoint is projected to the screen by hand ({@link WorldRender}) and drawn through
 * the HUD pipeline. State lives in {@link SBSConfig.MiningRoutesSettings}; the client tick and the
 * render pass are the same thread, so no locking is needed.
 */
public final class MiningRoutesManager {

    private static final MiningRoutesManager INSTANCE = new MiningRoutesManager();
    private static final Gson GSON = new Gson();

    private MiningRoutesManager() {
    }

    public static MiningRoutesManager getInstance() {
        return INSTANCE;
    }

    public static SBSConfig.MiningRoutesSettings cfg() {
        return ConfigManager.getInstance().get().miningRoutes;
    }

    private static void save() {
        // Route data lives in miningroutes.txt; the small settings (selected index, toggles) in config.
        MiningRouteStore.save();
        ConfigManager.getInstance().save();
    }

    public List<MiningRoute> routes() {
        return MiningRouteStore.routes();
    }

    /** The route new waypoints are appended to (created on demand if none exists). */
    public MiningRoute selected() {
        List<MiningRoute> routes = routes();
        if (routes.isEmpty()) {
            createRoute("Route 1");
        }
        int index = Math.max(0, Math.min(cfg().selectedRoute, routes.size() - 1));
        cfg().selectedRoute = index;
        return routes.get(index);
    }

    public MiningRoute createRoute(String name) {
        MiningRoute route = new MiningRoute();
        route.name = name == null || name.isBlank() ? "Route " + (routes().size() + 1) : name.trim();
        routes().add(route);
        cfg().selectedRoute = routes().size() - 1;
        save();
        return route;
    }

    public void deleteRoute(int index) {
        if (index >= 0 && index < routes().size()) {
            routes().remove(index);
            cfg().selectedRoute = Math.max(0, Math.min(cfg().selectedRoute, routes().size() - 1));
            save();
        }
    }

    // ------------------------------------------------------------------
    // Waypoint editing
    // ------------------------------------------------------------------

    /** Appends the block the player is standing on to the selected route. */
    public void addWaypointAtPlayer() {
        var player = Minecraft.getInstance().player;
        if (player == null || !cfg().enabled) {
            return;
        }
        int x = (int) Math.floor(player.getX());
        int y = (int) Math.floor(player.getY());
        int z = (int) Math.floor(player.getZ());
        MiningRoute route = selected();
        route.points.add(new int[]{x, y, z});
        save();
        SBSChat.send(net.minecraft.network.chat.Component.literal(
                " Waypoint #" + route.points.size() + " added to \"" + route.name + "\" ("
                        + x + ", " + y + ", " + z + ")").withColor(0x55FF55));
    }

    /** Removes the last waypoint from the selected route. */
    public void undoLastWaypoint() {
        MiningRoute route = selected();
        if (!route.points.isEmpty()) {
            route.points.remove(route.points.size() - 1);
            save();
        }
    }

    public void clearSelected() {
        selected().points.clear();
        save();
    }

    // ------------------------------------------------------------------
    // Import / export (clipboard)
    // ------------------------------------------------------------------

    /** A shareable string for a route: base64 of its JSON, so it survives a copy/paste intact. */
    public String exportRoute(int index) {
        if (index < 0 || index >= routes().size()) {
            return "";
        }
        String json = GSON.toJson(routes().get(index));
        return "SBSROUTE:" + Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Imports a route from a pasted string; returns the route name, or {@code null} on failure.
     *
     * <p>Parsing lives in {@link MiningRouteImport}, which accepts our own export alongside the route
     * shapes players actually share with each other - most of which are not ours. See that class for
     * what is recognised.
     */
    public String importRoute(String encoded) {
        MiningRoute route = MiningRouteImport.parse(encoded);
        if (route == null) {
            return null;
        }
        routes().add(route);
        cfg().selectedRoute = routes().size() - 1;
        save();
        return route.name;
    }

    // ------------------------------------------------------------------
    // World render (from HudMixin's world pass)
    // ------------------------------------------------------------------

    public void render(GuiGraphicsExtractor g) {
        SBSConfig.MiningRoutesSettings settings = cfg();
        List<MiningRoute> routes = MiningRouteStore.routes();
        if (!settings.enabled || routes.isEmpty()
                || !sbs.modid.client.skills.SkillIslands.miningAllowed()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        Camera camera = minecraft.gameRenderer.mainCamera();
        Vec3 camPos = camera.position();
        Matrix4f vp = camera.getViewRotationProjectionMatrix(new Matrix4f());
        Font font = minecraft.font;
        int thickness = Math.max(1, settings.lineWidth);
        int gw = g.guiWidth();
        int gh = g.guiHeight();

        for (MiningRoute route : routes) {
            if (!route.visible || route.points.isEmpty()) {
                continue;
            }
            int color = routeColor(route.colorHex);
            List<int[]> pts = route.points;
            // Connecting lines (waypoint centres), then optionally close the loop.
            for (int i = 0; i < pts.size() - 1; i++) {
                drawSegment(g, vp, camPos, pts.get(i), pts.get(i + 1), color, thickness, gw, gh);
            }
            if (route.loop && pts.size() > 2) {
                drawSegment(g, vp, camPos, pts.get(pts.size() - 1), pts.get(0), color, thickness, gw, gh);
            }
            // Waypoint markers + labels.
            for (int i = 0; i < pts.size(); i++) {
                int[] p = pts.get(i);
                if (settings.showWaypointBoxes) {
                    WorldRender.boxEdges(g, vp, camPos, p[0], p[1], p[2], p[0] + 1, p[1] + 1, p[2] + 1,
                            color, thickness);
                }
                if (settings.showLabels) {
                    int[] s = WorldRender.projectToScreen(vp, camPos,
                            new Vec3(p[0] + 0.5, p[1] + 1.2, p[2] + 0.5), gw, gh);
                    if (s != null) {
                        String label = "§f" + (i + 1);
                        g.text(font, Component.literal(label), s[0] - font.width(label) / 2, s[1], color, false);
                    }
                }
            }
        }
    }

    /** {@code RRGGBB} -> opaque ARGB, defaulting to sci-fi blue on a bad value. */
    public static int routeColor(String hex) {
        Integer rgb = OverlayColor.parseHex(hex);
        return rgb == null ? 0xFF3FB4FF : 0xFF000000 | rgb;
    }

    private static void drawSegment(GuiGraphicsExtractor g, Matrix4f vp, Vec3 camPos,
                                    int[] a, int[] b, int color, int thickness, int gw, int gh) {
        int[] sa = WorldRender.projectToScreen(vp, camPos, new Vec3(a[0] + 0.5, a[1] + 0.5, a[2] + 0.5), gw, gh);
        int[] sb = WorldRender.projectToScreen(vp, camPos, new Vec3(b[0] + 0.5, b[1] + 0.5, b[2] + 0.5), gw, gh);
        if (sa != null && sb != null) {
            WorldRender.line(g, sa[0], sa[1], sb[0], sb[1], color, thickness);
        }
    }
}
