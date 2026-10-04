/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.map;

import net.minecraft.client.Minecraft;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.ModuleSubgroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.helper.map.logic.MapDatabase;
import sbs.modid.client.helper.map.logic.MapNavigation;
import sbs.modid.client.helper.map.logic.StructureSharing;
import sbs.modid.client.helper.map.ui.HollowsMapScreen;
import sbs.modid.client.helper.map.ui.SkyBlockMapScreen;
import sbs.modid.client.helper.warp.WarpAvailability;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.List;

/**
 * SkyBlock Map module: a map of each island's places, and a click to be taken to one.
 *
 * <p>Self-registered via {@code META-INF/services/sbs.modid.client.core.module.SbsModule}; state in
 * {@link SBSConfig.MapSettings}, map data in {@link MapDatabase}.
 *
 * <p>Route guidance is drawn, never walked - see {@link MapNavigation}.
 */
public final class MapModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public MapModule() {
    }

    @Override
    public String id() {
        return "map";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.QUALITY_OF_LIFE;
    }

    @Override
    public ModuleSubgroup subgroup() {
        return ModuleSubgroup.NAVIGATION;
    }

    @Override
    public String displayName() {
        return "SkyBlock Map";
    }

    @Override
    public String description() {
        return "Maps of every island: click a place to warp there and get a route";
    }

    @Override
    public int accentColor() {
        return 0xFF5AC8FA;
    }

    private static SBSConfig.MapSettings cfg() {
        return ConfigManager.getInstance().get().map;
    }

    /** The shared pathfinding renderer's settings - the map draws its route with them. */
    private static SBSConfig.PathfindingSettings render() {
        return ConfigManager.getInstance().get().pathfinding;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    public static void openScreen() {
        Minecraft.getInstance().setScreenAndShow(new SkyBlockMapScreen(null));
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("SkyBlock Map", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("A map per island showing the places on it - shops, bosses, "
                                + "NPCs, warps - that you can click to be taken to. Off, the "
                                + "screen still opens but nothing is marked or routed."),
                SettingRow.label("Click a place on the map to travel there"),

                SettingRow.button("Open Map", MapModule::openScreen)
                        .describe("Opens the map. Also on /sbs map."),
                SettingRow.keybind("Open Map Key", () -> cfg().openKey,
                        key -> { cfg().openKey = key; save(); })
                        .describe("A key that opens the map while playing."),

                SettingRow.toggle("Auto Warp On Click", () -> cfg().autoWarp,
                        () -> { cfg().autoWarp = !cfg().autoWarp; save(); })
                        .describe("Lets a click run the warp commands that get you near the place - "
                                + "the island first if you are elsewhere, then the nearest warp on "
                                + "it. Off, the click only marks the spot and tells you which warp "
                                + "you would want."),
                SettingRow.label("Off: the click marks the spot but sends no commands"),

                SettingRow.toggle("Draw Route", () -> cfg().showRoute,
                        () -> { cfg().showRoute = !cfg().showRoute; save(); })
                        .describe("Draws the walking route from where you land to the marker. "
                                + "Off leaves just the marker and its distance."),
                SettingRow.label("§8Guidance is drawn only - nothing ever walks for you"),

                SettingRow.toggle("Marker Through Walls", () -> cfg().throughWalls,
                        () -> { cfg().throughWalls = !cfg().throughWalls; save(); })
                        .describe("Keeps the marker visible with terrain in the way. Off, it hides "
                                + "behind walls, which reads more naturally indoors."),
                SettingRow.toggle("Distance On Marker", () -> cfg().showDistance,
                        () -> { cfg().showDistance = !cfg().showDistance; save(); })
                        .describe("Puts the remaining distance in the marker's label."),
                SettingRow.toggle("Show Warps On Map", () -> cfg().showWarps,
                        () -> { cfg().showWarps = !cfg().showWarps; save(); })
                        .describe("Draws every catalogued warp arrival point on the map, greying "
                                + "out the ones this profile has been refused."),

                SettingRow.label("— Crystal Hollows —"),
                SettingRow.toggle("Crystal Hollows Structures", () -> cfg().hollowsDiscover,
                        () -> { cfg().hollowsDiscover = !cfg().hollowsDiscover; save(); })
                        .describe("Marks a structure on the Hollows map as you walk into it - the "
                                + "Temple, Divan, the Queen's Den and the rest. The Hollows are "
                                + "rebuilt every few hours, so what is found belongs to the lobby "
                                + "you are in: it comes back if you return to that lobby within 6 "
                                + "hours, and is dropped after that."),
                SettingRow.label("§8Found by the zone you are standing in - nothing is scanned"),
                SettingRow.toggle("Crystal Hollows Chat Pins", () -> cfg().hollowsChatPins,
                        () -> { cfg().hollowsChatPins = !cfg().hollowsChatPins; save(); })
                        .describe("Puts coordinates other players post in chat on the Hollows map "
                                + "too, with who said them. They use the chat waypoints you already "
                                + "have, so their lifetime is that setting in Chat Options. The map's "
                                + "extent is an estimate (ESTIMATED, not yet checked in game): a pin "
                                + "outside it is skipped until you have walked that far yourself."),

                SettingRow.toggle("Share Crystal Hollows Structures", () -> cfg().hollowsShare,
                        () -> { cfg().hollowsShare = !cfg().hollowsShare; save(); })
                        .describe("Shows the structures other SBS players in your Crystal Hollows "
                                + "lobby have walked into as waypoints, and shares the ones you walk "
                                + "into with them. Opens a connection to the SBS server while you are "
                                + "on the Hollows. Sent: the lobby id (such as mini24CD), which "
                                + "structure, its centre and box, your mod version and a short-lived "
                                + "ticket from your licence. Never your name, UUID or chat. Also needs "
                                + "\"Crystal Hollows structure sharing\" allowed in Licence Token > "
                                + "Privacy & data.")
                        .licenced(),
                SettingRow.toggle("Contribute My Finds", () -> cfg().hollowsShareContribute,
                        () -> { cfg().hollowsShareContribute = !cfg().hollowsShareContribute; save(); })
                        .describe("Sends the structures you walk into. Off, you still see what "
                                + "others shared, and nothing about your own finds is sent.")
                        .disabledWhile(() -> !cfg().hollowsShare),
                SettingRow.toggle("Show Unconfirmed Structures", () -> cfg().hollowsShareShowUnconfirmed,
                        () -> {
                            cfg().hollowsShareShowUnconfirmed = !cfg().hollowsShareShowUnconfirmed;
                            save();
                        })
                        .describe("Shows structures only one player has reported so far - as waypoints "
                                + "and on both maps - drawn fainter and labelled unconfirmed. Off, a "
                                + "shared structure appears once two players have reported it. Your "
                                + "own finds always show.")
                        .disabledWhile(() -> !cfg().hollowsShare),
                SettingRow.label("§8" + StructureSharing.getInstance().statusLine()),
                SettingRow.label("§8A structure nobody has reported yet may still exist"),

                SettingRow.button("Open Crystal Hollows Map", HollowsMapScreen::open)
                        .describe("A schematic map of the Hollows: the four quadrants, the Nucleus and "
                                + "Magma Fields as drawn regions, the cells you have walked, the "
                                + "structures found in this lobby and your own markers. Also on "
                                + "/sbs chmap. Nothing on it comes from the terrain; structures other "
                                + "players shared appear only with sharing switched on above."),
                SettingRow.keybind("Crystal Hollows Map Key", () -> cfg().hollowsMapKey,
                        key -> { cfg().hollowsMapKey = key; save(); })
                        .describe("A key that opens the Crystal Hollows map. Unbound by default."),
                SettingRow.toggle("Crystal Hollows Minimap", () -> cfg().hollowsMinimap,
                        () -> { cfg().hollowsMinimap = !cfg().hollowsMinimap; save(); })
                        .describe("A small square of the Hollows map on the HUD, only while you are on "
                                + "the Crystal Hollows. Move and scale it in the HUD editor. Off by "
                                + "default."),
                SettingRow.rangeSlider("Minimap Size", 64, 192, () -> cfg().hollowsMinimapSize,
                        value -> { cfg().hollowsMinimapSize = value; save(); }, "px")
                        .describe("Side length of the minimap square. Default 112."),
                SettingRow.rangeSlider("Minimap Radius", 32, 256, () -> cfg().hollowsMinimapRadius,
                        value -> { cfg().hollowsMinimapRadius = value; save(); }, " blocks")
                        .describe("How far from you the minimap reaches, centre to edge. Anything "
                                + "further sits on the edge in its direction. Default 96."),
                SettingRow.toggle("Rotate Minimap With You", () -> cfg().hollowsMinimapRotate,
                        () -> { cfg().hollowsMinimapRotate = !cfg().hollowsMinimapRotate; save(); })
                        .describe("Turns the minimap so the way you face is up, with an N on the edge. "
                                + "Off keeps north up. Default off."),
                SettingRow.toggle("Show Explored Trail", () -> cfg().hollowsShowTrail,
                        () -> { cfg().hollowsShowTrail = !cfg().hollowsShowTrail; save(); })
                        .describe("Shades the 8x8 cells you have stood in this lobby, upper cave and "
                                + "Magma Fields separately. Only your own position is recorded. "
                                + "Default on."),
                SettingRow.toggle("Show My Markers", () -> cfg().hollowsShowMarkers,
                        () -> { cfg().hollowsShowMarkers = !cfg().hollowsShowMarkers; save(); })
                        .describe("Draws the markers you placed with /sbs chmap mark <label>. They are "
                                + "kept on this PC only, 30 per lobby. Default on."),
                SettingRow.label("§8Quadrant layout is ESTIMATED - see the map's footer"),

                SettingRow.rangeSlider("Map Zoom", 40, 600, () -> cfg().zoom,
                        value -> { cfg().zoom = value; save(); }, "%")
                        .describe("Starting zoom of the map. The scroll wheel changes it too, and "
                                + "whatever you leave it at is remembered."),

                // The route and marker are drawn by the shared pathfinding renderer, so these are the
                // same switches the pathfinding module offers - changing them here changes them there.
                SettingRow.enumOptions("Route Style", () -> render().pathStyle,
                        value -> { render().pathStyle = value; save(); }, v -> v.displayName())
                        .describe("How the route is drawn: a trail of cubes, a connected line, or "
                                + "both. Shared with the pathfinding module - one setting, not two."),
                SettingRow.enumOptions("Marker Colour", () -> render().waypointColor,
                        value -> {
                            render().waypointColor = value;
                            render().waypointColorHex = "";
                            save();
                        }, v -> v.displayName())
                        .describe("Colour of the marker box and its beam. Shared with the "
                                + "pathfinding module."),
                SettingRow.enumOptions("Route Colour", () -> render().pathColor,
                        value -> {
                            render().pathColor = value;
                            render().pathColorHex = "";
                            save();
                        }, v -> v.displayName())
                        .describe("Colour of the route line. Shared with the pathfinding module."),

                SettingRow.button("Forget Warp Unlocks", WarpAvailability::reset)
                        .describe("Clears what this profile has learned about which warps are "
                                + "unlocked. Worth doing after unlocking one: Hypixel says nothing "
                                + "when a warp becomes available, so a warp refused in the past "
                                + "stays marked locked until this is cleared."),

                SettingRow.label("§8" + MapDatabase.maps().size() + " island map(s), "
                        + places() + " place(s) catalogued"),
                SettingRow.label("§8" + WarpAvailability.knownCount()
                        + " warp(s) on record for this profile"),
                SettingRow.label("§8" + MapNavigation.getInstance().statusLine()),
                SettingRow.label("§8Coordinates are seed data - one Hypixel moved may be stale"));
    }

    private static int places() {
        int total = 0;
        for (var map : MapDatabase.maps()) {
            total += map.locations.size();
        }
        return total;
    }
}
