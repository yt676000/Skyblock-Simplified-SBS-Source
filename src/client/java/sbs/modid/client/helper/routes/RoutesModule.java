/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.routes;

import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.core.pathfinding.PathRenderer;
import sbs.modid.client.core.pathfinding.PathfindingManager;
import sbs.modid.client.core.pathfinding.RouteSource;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.ui.HudEditorScreen;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.ArrayList;
import java.util.List;

/**
 * Routes (multi-route pathfinding): how the routes every feature asks for are drawn - one colour and
 * one destination marker per source, the cap on how many run at once, and the Route List card.
 *
 * <p>Whether a source routes at all is <b>not</b> here: each feature's own toggle (Fairy Souls'
 * "Pathfind", the NPC module's objective routing, the map's "Show Route"...) stays the switch, under
 * its existing config id. This page only styles what those switches turn on.
 */
public final class RoutesModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public RoutesModule() {
    }

    @Override
    public String id() {
        return "routes";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.QUALITY_OF_LIFE;
    }

    @Override
    public String displayName() {
        return "Routes";
    }

    @Override
    public String description() {
        return "Every feature's route at once, each in its own colour with a marker at its end";
    }

    @Override
    public int accentColor() {
        return 0xFF4FC3F7;
    }

    private static SBSConfig.PathfindingSettings cfg() {
        return ConfigManager.getInstance().get().pathfinding;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        List<SettingRow> rows = new ArrayList<>();
        rows.add(SettingRow.label("Each feature's own toggle still switches its route on or off"));
        rows.add(SettingRow.intField("Max Routes At Once", 1, 8, () -> cfg().maxRoutes,
                        value -> { cfg().maxRoutes = value; save(); }, "")
                .describe("How many routes may be searched and drawn at the same time. Past this "
                        + "the least important ones are paused (the map click and the Quest Guide "
                        + "outrank the objective, which outranks secrets, Hideyho, fairy souls and "
                        + "commissions) and come back when a slot frees up."));
        rows.add(SettingRow.toggle("Markers Through Walls", () -> cfg().routeMarkersThroughWalls,
                        () -> { cfg().routeMarkersThroughWalls = !cfg().routeMarkersThroughWalls; save(); })
                .describe("Draws each route's destination pillar and label even when terrain is in "
                        + "the way. Off hides a marker you cannot see directly."));
        rows.add(SettingRow.toggle("Route List", () -> cfg().routeHudList,
                        () -> { cfg().routeHudList = !cfg().routeHudList; save(); })
                .describe("A small card listing the active routes with their colour, where they "
                        + "lead and how far. Only shows while two or more routes are active."));
        rows.add(SettingRow.button("Move / Resize Route List", () -> open(new HudEditorScreen(
                        new HudElement[] {HudElement.ROUTE_LIST}, "Edit Route List")))
                .describe("Opens the editor where you drag the Route List anywhere on the screen "
                        + "and scale it."));

        for (RouteSource source : RouteSource.values()) {
            String name = sourceLabel(source);
            rows.add(SettingRow.label(name));
            rows.add(SettingRow.color(name + " Route Colour", () -> hex(source),
                            () -> 0xFF000000 | PathRenderer.routeRgb(cfg(), source),
                            () -> openPicker(name, source))
                    .describe("The colour of the " + name.toLowerCase(java.util.Locale.ROOT)
                            + " route and its destination marker. Clear it in the picker to go back "
                            + "to the default."));
            rows.add(SettingRow.toggle(name + " Route Marker", () -> cfg().routeMarker(source),
                            () -> {
                                if (!cfg().routeMarkersOff.remove(source.id())) {
                                    cfg().routeMarkersOff.add(source.id());
                                }
                                save();
                            })
                    .describe("A pillar at the end of the " + name.toLowerCase(java.util.Locale.ROOT)
                            + " route, with its name and the distance along the route."));
        }
        rows.add(SettingRow.label(PathfindingManager.getInstance().routes().size() + " route(s) active"));
        return rows;
    }

    /** The settings-page name of a source; the marker uses the shorter {@link RouteSource#displayName}. */
    private static String sourceLabel(RouteSource source) {
        return switch (source) {
            case MAP -> "Map Click";
            case QUEST -> "Quest Guide";
            case OBJECTIVE -> "Scoreboard Objective";
            case SECRETS -> "Dungeon Secrets";
            case HIDEYHO -> "Hideyho";
            case FAIRY_SOULS -> "Fairy Souls";
            case COMMISSIONS -> "Commissions";
            case DEV -> "Dev Waypoints";
        };
    }

    private static String hex(RouteSource source) {
        String hex = cfg().routeColorHex.get(source.id());
        return hex == null ? "" : hex;
    }

    private static void openPicker(String label, RouteSource source) {
        net.minecraft.client.gui.screens.Screen previous = GuiStateManager.getInstance().getCurrentScreen();
        open(new sbs.modid.client.ui.theme.ThemeColorPickerScreen("Routes  •  " + label, hex(source),
                value -> {
                    if (value == null || value.isBlank()) {
                        cfg().routeColorHex.remove(source.id());
                    } else {
                        cfg().routeColorHex.put(source.id(), value);
                    }
                    save();
                }, previous));
    }

    private static void open(net.minecraft.client.gui.screens.Screen screen) {
        net.minecraft.client.Minecraft.getInstance().setScreenAndShow(screen);
    }
}
