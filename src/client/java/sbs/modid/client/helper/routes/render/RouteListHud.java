/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.routes.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.pathfinding.PathRenderer;
import sbs.modid.client.core.pathfinding.PathfindingManager;
import sbs.modid.client.core.pathfinding.Route;
import sbs.modid.client.core.pathfinding.Waypoint;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.List;

/**
 * The Route List card: one line per route - a dot in the route's colour, what it leads to, and how
 * far, or why it is not drawing (paused by the cap, searching, no route known).
 *
 * <p>Only drawn while two or more routes exist: with one route the line on the ground already says
 * everything, and a card repeating it is clutter. Movable and scalable through the GUI editor.
 */
public final class RouteListHud {

    private static final int PAD = 5;
    private static final int DOT = 5;
    private static final int GAP = 4;
    private static final int MIN_W = 110;

    private RouteListHud() {
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        SBSConfig.PathfindingSettings cfg = ConfigManager.getInstance().get().pathfinding;
        if (!cfg.routeHudList || HudLayout.isHidden(HudElement.ROUTE_LIST)) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        List<Route> routes = PathfindingManager.getInstance().routes();
        if (routes.size() < 2) {
            return;
        }
        HudElement.Bounds bounds = HudElement.ROUTE_LIST.defaultBounds(g.guiWidth(), g.guiHeight());
        HudLayout.begin(g, HudElement.ROUTE_LIST);
        draw(g, minecraft, cfg, routes, (int) bounds.x(), (int) bounds.y());
        HudLayout.end(g);
    }

    private static void draw(GuiGraphicsExtractor g, Minecraft minecraft, SBSConfig.PathfindingSettings cfg,
                             List<Route> routes, int x, int y) {
        Font font = minecraft.font;
        Route primary = PathfindingManager.getInstance().primary();
        String[] lines = new String[routes.size()];
        int contentW = font.width("Routes");
        for (int i = 0; i < routes.size(); i++) {
            lines[i] = line(minecraft, routes.get(i));
            contentW = Math.max(contentW, DOT + GAP + font.width(lines[i]));
        }
        int lineH = font.lineHeight + 2;
        int width = Math.max(MIN_W, PAD * 2 + contentW);
        int height = PAD * 2 + lineH * (routes.size() + 1);
        HudLayout.measure(HudElement.ROUTE_LIST, x, y, width, height);
        HudCard.draw(g, x, y, width, height);

        int ix = x + PAD;
        int iy = y + PAD;
        g.text(font, Component.literal("Routes"), ix, iy, SBSTheme.ACCENT);
        for (int i = 0; i < routes.size(); i++) {
            iy += lineH;
            Route route = routes.get(i);
            int rgb = PathRenderer.routeRgb(cfg, route.source());
            boolean dimmed = route.paused() || route.stale();
            SciFiRender.roundedRect(g, ix, iy + (font.lineHeight - DOT) / 2, DOT, DOT, 2,
                    (dimmed ? 0x80000000 : 0xFF000000) | rgb);
            int text = route == primary ? SBSTheme.TEXT : SBSTheme.TEXT_MUTED;
            g.text(font, Component.literal(lines[i]), ix + DOT + GAP, iy, text);
        }
    }

    /** "Bartender  18m", "Fairy Soul  42m", "Commission  paused". */
    private static String line(Minecraft minecraft, Route route) {
        Waypoint target = route.target();
        String name = target == null || route.goals().size() > 1 || target.name == null || target.name.isBlank()
                ? route.source().displayName() : target.name;
        String tail = switch (route.state()) {
            case PAUSED -> "§7paused";
            case SEARCHING -> "§7searching";
            case NO_ROUTE -> "§cno route";
            case ARRIVED -> "§aarrived";
            case STALE -> "§7lost";
            case ROUTE, PARTIAL -> {
                double metres = route.reachedGoal() ? route.length()
                        : target == null ? 0 : Math.sqrt(minecraft.player.distanceToSqr(
                                target.x + 0.5, target.y, target.z + 0.5));
                yield Math.round(metres) + "m" + (route.reachedGoal() ? "" : " §c~");
            }
        };
        return name + "  " + tail;
    }
}
