/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.map.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.location.hollows.HollowsDetector;
import sbs.modid.client.core.location.hollows.HollowsGeometry;
import sbs.modid.client.core.render.WorldRender;
import sbs.modid.client.helper.map.logic.HollowsMapTracker;
import sbs.modid.client.helper.map.logic.HollowsTarget;
import sbs.modid.client.helper.map.model.HollowsLobbyMap;
import sbs.modid.client.helper.map.model.HollowsMarker;
import sbs.modid.client.helper.map.model.HollowsTrail;
import sbs.modid.client.helper.map.model.KnownStructure;
import sbs.modid.client.helper.map.model.MapViewport;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.render.HudCard;

/**
 * The Crystal Hollows minimap: a small square of the schematic map around you, north-up or turning
 * with you. Off by default, and drawn only on the Hollows.
 *
 * <p>The same painter as the full-screen map, minus the list. A structure or marker beyond the radius
 * sits on the square's edge in its direction, with a short tick pointing outward, so "which way" is
 * still answered when "how far" is not on screen.
 */
public final class HollowsMinimapHud {

    /** Pixels kept between an edge-clamped icon and the frame. */
    private static final int EDGE = 6;

    private static final MapViewport VIEWPORT = new MapViewport(HollowsGeometry.MIN, HollowsGeometry.MIN,
            HollowsGeometry.MAX, HollowsGeometry.MAX);

    private HollowsMinimapHud() {
    }

    public static void render(GuiGraphicsExtractor g) {
        SBSConfig.MapSettings cfg = ConfigManager.getInstance().get().map;
        if (!cfg.enabled || !cfg.hollowsMinimap || HudLayout.isHidden(HudElement.CH_MINIMAP)
                || !HollowsDetector.getInstance().onHollows()) {
            return;
        }
        Player player = Minecraft.getInstance().player;
        HollowsLobbyMap map = HollowsMapTracker.getInstance().current();
        if (player == null || map == null) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        int size = Math.max(48, Math.min(256, cfg.hollowsMinimapSize));
        int radius = Math.max(16, cfg.hollowsMinimapRadius);
        HudElement.Bounds b = HudElement.CH_MINIMAP.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());

        HudLayout.measure(HudElement.CH_MINIMAP, x, y, size, size);
        HudLayout.begin(g, HudElement.CH_MINIMAP);
        HudCard.draw(g, x, y, size, size);

        MapViewport vp = VIEWPORT;
        vp.setCanvas(x + 1, y + 1, size - 2, size - 2);
        vp.setScale((size / 2.0 - 1) / radius);
        vp.setCenter(player.getX(), player.getZ());
        vp.setRotation(cfg.hollowsMinimapRotate ? MapViewport.rotationFacingUp(player.getYRot()) : 0);

        HollowsTrail.Layer layer = HollowsTrail.Layer.of(player.getBlockY());
        g.enableScissor(x + 1, y + 1, x + size - 1, y + size - 1);
        HollowsMapPainter.regions(g, vp, layer, 0x40);
        if (cfg.hollowsShowTrail) {
            HollowsMapPainter.trail(g, vp, map.trail(), layer, 0x50);
        }

        for (KnownStructure known : HollowsMapTracker.getInstance().known()) {
            if (!known.confirmed() && !cfg.hollowsShareShowUnconfirmed) {
                continue;
            }
            double[] p = clampToEdge(vp, known.x(), known.z(), size, g);
            int rgb = HollowsGeometry.classify(known.x(), known.y(), known.z()).rgb();
            HollowsMapPainter.structureIcon(g, font, (int) Math.round(p[0]), (int) Math.round(p[1]),
                    known.structure().glyph(), rgb, known.confirmed(), HollowsMapPainter.ICON);
        }
        if (cfg.hollowsShowMarkers) {
            for (HollowsMarker marker : map.markers()) {
                double[] p = clampToEdge(vp, marker.x(), marker.z(), size, g);
                HollowsMapPainter.markerIcon(g, (int) Math.round(p[0]), (int) Math.round(p[1]),
                        HollowsMapPainter.MARKER - 2);
            }
        }
        HollowsTarget.Target target = HollowsTarget.getInstance().current();
        if (target != null) {
            double[] p = clampToEdge(vp, target.x(), target.z(), size, g);
            sbs.modid.client.ui.render.SciFiRender.ring(g, (int) Math.round(p[0]) - 6,
                    (int) Math.round(p[1]) - 6, 12, 12, 6, 0xFF57D977);
        }
        if (cfg.hollowsMinimapRotate) {
            // Where north is, since the square no longer says.
            double[] north = clampToEdge(vp, player.getX(), player.getZ() - radius * 4.0, size, null);
            g.centeredText(font, Component.literal("N"), (int) Math.round(north[0]),
                    (int) Math.round(north[1]) - font.lineHeight / 2, 0xFFE0E6EC);
        }
        double[] dir = vp.screenDirection(player.getYRot());
        HollowsMapPainter.playerArrow(g, vp.canvasCenterX(), vp.canvasCenterY(), dir[0], dir[1], 5, 0x57D977);
        g.disableScissor();
        HudLayout.end(g);
    }

    /**
     * The screen position for a world point, pulled onto the square's edge when it lies outside -
     * with an outward tick drawn there when {@code g} is given.
     */
    private static double[] clampToEdge(MapViewport vp, double worldX, double worldZ, int size,
                                        GuiGraphicsExtractor g) {
        double[] p = vp.toScreen(worldX, worldZ);
        double cx = vp.canvasCenterX();
        double cy = vp.canvasCenterY();
        double dx = p[0] - cx;
        double dy = p[1] - cy;
        double half = size / 2.0 - EDGE;
        double far = Math.max(Math.abs(dx), Math.abs(dy));
        if (far <= half) {
            return p;
        }
        double t = half / far;
        double ex = cx + dx * t;
        double ey = cy + dy * t;
        if (g != null) {
            double length = Math.hypot(dx, dy);
            int tx = (int) Math.round(ex + dx / length * (EDGE - 1));
            int ty = (int) Math.round(ey + dy / length * (EDGE - 1));
            WorldRender.line(g, (int) Math.round(ex), (int) Math.round(ey), tx, ty, 0xFFE0E6EC, 1);
        }
        return new double[]{ex, ey};
    }
}
