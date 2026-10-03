/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.secretroutes;

import net.minecraft.client.Minecraft;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.dungeons.secretroutes.logic.SecretRouteStore;
import sbs.modid.client.ui.settings.SettingRow;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;

import java.util.List;

/**
 * Secret Routes module (Dungeons): record, annotate, save and render secret routes per dungeon room.
 * Self-registered via {@code META-INF/services/sbs.modid.client.core.module.SbsModule}; state lives in
 * {@link SBSConfig.SecretRoutesSettings} and the routes themselves in
 * {@code config/sbs/secretroutes.txt} via {@link SecretRouteStore}.
 *
 * <p>Reuses the existing Pathfinding renderer for the movement line and the scanned-room database for
 * room recognition + rotation - nothing here re-implements either.
 */
public final class SecretRoutesModule implements SbsModule {

    public SecretRoutesModule() {
    }

    @Override
    public String id() {
        return "secret_routes";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.DUNGEONS;
    }

    @Override
    public String displayName() {
        return "Secret Routes";
    }

    @Override
    public String description() {
        return "Record, annotate and render per-room dungeon secret routes (rotation-aware)";
    }

    @Override
    public int accentColor() {
        return 0xFF3FB4FF;
    }

    private static SBSConfig.SecretRoutesSettings cfg() {
        return ConfigManager.getInstance().get().secretRoutes;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    public static void openScreen() {
        Minecraft.getInstance().setScreenAndShow(
                new sbs.modid.client.dungeons.secretroutes.ui.SecretRoutesScreen(null));
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Secret Routes", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("Record your own secret routes through dungeon rooms and have "
                                + "them drawn in the world on every later run. A route is saved "
                                + "per room and rotates with it, so it fits no matter how the room "
                                + "is turned in this dungeon."),
                SettingRow.label("Record + render secret routes per room; routes rotate with the run"),

                SettingRow.button("Open Editor", SecretRoutesModule::openScreen)
                        .describe("Opens the route editor: browse the routes of the room you are "
                                + "in, reorder points, write notes, delete mistakes."),

                SettingRow.toggle("Show Routes", () -> cfg().showRoutes,
                        () -> { cfg().showRoutes = !cfg().showRoutes; save(); })
                        .describe("Draws the recorded route of the room you are in: its points and "
                                + "the lines between them."),
                // Renamed from "Show Pathfinding", which now says the wrong thing: the row below is
                // the pathfinder, and this one only ever drew the recorded walk. The old slug is kept
                // as an alias so a stored favourite or jump target still resolves - a rename without
                // one silently unfavourites the row.
                SettingRow.toggle("Show Recorded Path", () -> cfg().showPathfinding,
                        () -> { cfg().showPathfinding = !cfg().showPathfinding; save(); })
                        .anchor("show_recorded_path", "show_pathfinding")
                        .describe("Draws the line you walked when you recorded the route, or - if "
                                + "you never recorded a walk - a straight line joining the points "
                                + "in order. This is the route as saved; it does not know where you "
                                + "are standing. On by default."),
                SettingRow.toggle("Pathfind to Secrets", () -> cfg().pathfind,
                        () -> {
                            cfg().pathfind = !cfg().pathfind;
                            save();
                            // The router caches its route and the reasons it may reuse it; without
                            // this, switching the setting off leaves the last path on screen.
                            sbs.modid.client.core.pathfinding.PathfindingManager.getInstance().invalidate();
                        })
                        .describe("Walks you to the nearest secret you have not collected yet in "
                                + "this room, along a path worked out from where you are actually "
                                + "standing - so it still works when you join a room halfway or go "
                                + "round it backwards. It picks the next one by itself as you "
                                + "collect them. \"Nearest\" means by walking distance, not straight "
                                + "line: a chest four blocks through the wall behind you is a long "
                                + "way away. On by default."),
                SettingRow.label("§8" + sbs.modid.client.dungeons.secretroutes.logic.SecretRouting
                        .getInstance().statusLine()),
                SettingRow.toggle("Show Descriptions", () -> cfg().showDescriptions,
                        () -> { cfg().showDescriptions = !cfg().showDescriptions; save(); })
                        .describe("Shows the note you wrote on a point (e.g. 'lever behind "
                                + "painting') floating at that point."),
                SettingRow.toggle("Dim Passed Points", () -> cfg().dimPassed,
                        () -> { cfg().dimPassed = !cfg().dimPassed; save(); })
                        .describe("Fades the points you have already walked past, so the next one "
                                + "stands out."),
                SettingRow.toggle("Show Breaker Blocks", () -> cfg().showBreakerBlocks,
                        () -> { cfg().showBreakerBlocks = !cfg().showBreakerBlocks; save(); })
                        .describe("Highlights blocks a route point says to break (e.g. for a "
                                + "hidden chest behind them)."),
                SettingRow.label("Path colour follows the Pathfinding module's Path Color"),

                SettingRow.intField("Line Width", 1, 6, () -> cfg().lineWidth,
                        value -> { cfg().lineWidth = value; save(); }, "px")
                        .describe("Thickness of the route lines in pixels."),
                SettingRow.intField("Aim Tolerance", 1, 10, () -> cfg().aimToleranceDeg,
                        value -> { cfg().aimToleranceDeg = value; save(); }, "°")
                        .describe("How precisely an AOTV-warp point requires you to aim (in "
                                + "degrees) before it counts as lined up. Smaller = stricter."),
                SettingRow.toggle("Depth Check (raycast)", () -> cfg().depthCheck,
                        () -> { cfg().depthCheck = !cfg().depthCheck; save(); })
                        .describe("Hides route points that are behind a wall from your viewpoint, "
                                + "approximated with a ray check. Off, everything shows through "
                                + "walls."),
                SettingRow.label("Depth Check is a raycast approximation - no true occlusion in the HUD pass"),

                SettingRow.keybind("Scan Items Key", () -> cfg().scanItemKey,
                        key -> { cfg().scanItemKey = key; save(); })
                        .describe("While recording: press to add the secret items around you "
                                + "(chests, levers...) as route points in one go."),
                SettingRow.keybind("Add Standing Key", () -> cfg().addStandingKey,
                        key -> { cfg().addStandingKey = key; save(); })
                        .describe("While recording: press to add the block you are standing on as "
                                + "a walk-here route point."),
                SettingRow.keybind("Add AOTV Warp Key", () -> cfg().addAotvKey,
                        key -> { cfg().addAotvKey = key; save(); })
                        .describe("While recording: press to add an AOTV teleport as a route "
                                + "point, aimed the way you are looking right now."),
                SettingRow.keybind("Add Pearl Key", () -> cfg().addPearlKey,
                        key -> { cfg().addPearlKey = key; save(); })
                        .describe("While recording: press to add an ender-pearl throw as a route "
                                + "point, aimed the way you are looking right now."),
                SettingRow.keybind("Open Editor Key", () -> cfg().openKey,
                        key -> { cfg().openKey = key; save(); })
                        .describe("A key that opens the route editor while playing."),
                SettingRow.label("Stand in a recognised room, then press a key to place a point"));
    }
}
