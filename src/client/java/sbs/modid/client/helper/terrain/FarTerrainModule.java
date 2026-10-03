/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.terrain;

import net.minecraft.client.Minecraft;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.List;

/**
 * Far Terrain module (Visuals): remembers island terrain and serves it back to the renderer, so a
 * supported island stays visible far beyond the few chunks Hypixel streams - and is already there
 * when you rejoin. Self-registered via
 * {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 */
public final class FarTerrainModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public FarTerrainModule() {
    }

    @Override
    public String id() {
        return "far_terrain";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.VISUALS;
    }

    @Override
    public String displayName() {
        return "Far Terrain";
    }

    @Override
    public String description() {
        return "Remembers island terrain and keeps it rendered far beyond what the server streams";
    }

    @Override
    public int accentColor() {
        return 0xFF3FB4FF;
    }

    private static SBSConfig.FarTerrainSettings cfg() {
        return ConfigManager.getInstance().get().farTerrain;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    /**
     * Applies a render-distance change, switching the module on first if it is off.
     *
     * <p>Dragging this slider past vanilla's ceiling is only meaningful with the module running -
     * with it off there is no remembered terrain to put out there, so the extra distance would draw
     * empty space and look broken. Turning it on is what the player plainly meant.
     */
    private static void setRenderDistance(int value) {
        if (cfg().mode == FarTerrainMode.OFF && value > FarTerrainRenderDistance.VANILLA_MAX) {
            cfg().mode = FarTerrainMode.ON;
            FarTerrainRenderDistance.sync(true);
        }
        // Writes Minecraft's option and this mod's saved copy together. Setting only the option left
        // the two to drift the moment the module was off, because nothing was mirroring it back.
        FarTerrainRenderDistance.setFromPlayer(value);
    }

    /** Live line under the framerate target: what the controller is currently doing about it. */
    private static String autoFpsNote() {
        if (!cfg().autoFps) {
            return "Automatically trade far terrain for frames";
        }
        if (FarTerrainAutoDistance.standingDown()) {
            return "§eFramerate is not terrain-bound here - distance handed back";
        }
        Integer held = FarTerrainAutoDistance.viewChunks();
        int fps = Minecraft.getInstance().getFps();
        if (held == null || held >= FarTerrainRenderDistance.current()) {
            return "Target met at " + fps + " fps - full distance drawn";
        }
        return "§b" + fps + " fps - drawing " + held + " ch of "
                + FarTerrainRenderDistance.current() + " ch";
    }

    /**
     * When the delete-everything button was last armed. A second press within {@link #CONFIRM_MS}
     * carries it out - the row sits directly under the single-map delete, and losing every map to
     * a misread label would cost a walk of the whole world to undo.
     */
    private static long deleteAllArmedAt;

    private static final long CONFIRM_MS = 5_000;

    private static boolean deleteAllArmed() {
        return System.currentTimeMillis() - deleteAllArmedAt < CONFIRM_MS;
    }

    private static void onDeleteAll() {
        if (deleteAllArmed()) {
            deleteAllArmedAt = 0;
            FarTerrainManager.getInstance().deleteAllMaps();
            return;
        }
        deleteAllArmedAt = System.currentTimeMillis();
    }

    /** One line naming what the claim record knows about this map's islands. */
    private static String overlapLine() {
        var claims = FarTerrainManager.getInstance().claims();
        if (claims == null) {
            return "No map open - island claims are recorded while you play";
        }
        java.util.List<String> islands = claims.islandsBySize();
        if (islands.isEmpty()) {
            return "No islands recorded on this map yet";
        }
        int pairs = 0;
        for (int i = 0; i < islands.size(); i++) {
            for (int j = i + 1; j < islands.size(); j++) {
                if (claims.overlapCount(islands.get(i), islands.get(j)) > 0) {
                    pairs++;
                }
            }
        }
        return islands.size() + " island(s) recorded, " + (pairs == 0
                ? "none overlapping" : "§c" + pairs + " overlapping pair(s) - see the log");
    }

    /** Live "how much of this is real" line under the render-distance row. */
    private static String renderDistanceNote() {
        int current = FarTerrainRenderDistance.current();
        int real = FarTerrainRenderDistance.realPortion();
        if (current <= real) {
            return current + " ch, all live server terrain";
        }
        return real + " ch live server terrain + " + (current - real) + " ch remembered";
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.enumOptions("Mode", () -> cfg().mode,
                        value -> { cfg().mode = value; save(); }, v -> v.displayName())
                        .describe("Off: nothing is remembered or shown. Performance: terrain radius "
                                + "capped at " + FarTerrainManager.PERFORMANCE_RADIUS + " chunks and "
                                + "remembered chunks stream in slowly, for weaker machines. On: the "
                                + "full radius below, streamed in quickly."),
                SettingRow.label("Terrain returns on rejoin; new chunks overwrite the memory"),
                SettingRow.holdKeybind("Toggle Key", () -> cfg().toggleKey,
                        value -> { cfg().toggleKey = value; save(); })
                        .describe("Switches the module off and back on without opening this menu, "
                                + "with a popup naming the new state - for dropping it the moment a "
                                + "fight gets busy. Coming back on returns the mode you were using, "
                                + "so a Performance setting is not quietly turned into On. Does not "
                                + "fire while chat or a menu is open. A mouse button works too; the "
                                + "wheel does not, because this one watches for the key going down."),
                SettingRow.label(cfg().toggleKey == 0
                        ? "Unbound - bind a key to switch this off mid-fight"
                        : "§8Now: " + FarTerrainToggle.currentSetting()),
                SettingRow.rangeSlider("Extra Chunks", 1, FarTerrainManager.MAX_RADIUS,
                        () -> cfg().extraChunks,
                        value -> { cfg().extraChunks = value; save(); }, "ch")
                        .describe("How many chunks around you remembered terrain is kept and served "
                                + "for. Pair it with the Render Distance row below - that is what "
                                + "decides how much of it is actually drawn."),

                // Render distance is Minecraft's own option, surfaced here because it is the ceiling
                // on everything above and is useless to set without the module on.
                SettingRow.rangeSlider("Render Distance", 2, FarTerrainManager.MAX_RADIUS,
                        FarTerrainRenderDistance::current, FarTerrainModule::setRenderDistance, "ch")
                        .describe("Minecraft's own render distance, raised past its usual "
                                + FarTerrainRenderDistance.VANILLA_MAX + " so far terrain can be "
                                + "drawn at all. Only the first "
                                + FarTerrainRenderDistance.VANILLA_MAX + " chunks are live server "
                                + "terrain - everything past that is remembered terrain from this "
                                + "module, so it is a snapshot and will not show other players or "
                                + "recent block changes. Costs memory with the square of the "
                                + "distance; back it off if the game struggles."),
                SettingRow.label(renderDistanceNote()),

                SettingRow.toggle("Uncapped", () -> cfg().uncapped,
                        () -> { cfg().uncapped = !cfg().uncapped; save(); })
                        .describe("Loads the map's ENTIRE remembered file at once, keeps all of it "
                                + "live and raises the drawn distance to reach the furthest chunk "
                                + "in it - the whole island visible in one go, no walking toward "
                                + "anything. The fill is deliberately aggressive and will stutter "
                                + "for its first seconds on a big map. This is the setting that can "
                                + "hurt: every loaded chunk costs memory, and a server transfer has "
                                + "to free all of it, which is what makes \"Reconfiguring\" hang. "
                                + "Turn it off first if the game stutters or a warp stalls."),
                SettingRow.label(cfg().uncapped
                        ? "§eOverrides the sliders, the indoor saver and the fps target"
                        : "Load the whole map file instead of a radius around you"),
                SettingRow.label(cfg().uncapped
                        ? "§eWatch memory; turn off if warps stall"
                        : "§8One remesh when it first grows the view, then it stays"),

                SettingRow.toggle("Hold A Framerate", () -> cfg().autoFps,
                        () -> { cfg().autoFps = !cfg().autoFps; save(); })
                        .describe("Gives far terrain back automatically until the framerate below "
                                + "is met, and takes it again when there is room. It sits still "
                                + "while the target is met, drops quickly when it is missed and "
                                + "climbs back slowly - raising the distance costs a mesh rebuild, "
                                + "so a controller that fiddled constantly would cost more than it "
                                + "saved. If giving up distance turns out not to help, it hands the "
                                + "distance straight back: a framerate held down by anything other "
                                + "than terrain is not something this can fix, and stripping the "
                                + "view away chasing it would be pure loss."),
                SettingRow.rangeSlider("Target FPS",
                        FarTerrainAutoDistance.MIN_TARGET, FarTerrainAutoDistance.MAX_TARGET,
                        () -> cfg().targetFps,
                        value -> { cfg().targetFps = value; save(); }, " fps")
                        .describe("The framerate to hold. Only meaningful when the terrain is what "
                                + "is costing the frames - above roughly 150 most machines are "
                                + "limited by the GPU, the driver or vsync instead, and this will "
                                + "notice that and stop rather than take your view away for "
                                + "nothing."),
                SettingRow.label(autoFpsNote()),

                SettingRow.toggle("Save Frames Indoors", () -> cfg().pauseWhenEnclosed,
                        () -> { cfg().pauseWhenEnclosed = !cfg().pauseWhenEnclosed; save(); })
                        .describe("While you are somewhere the far view is hidden anyway - a cave, a "
                                + "mine, a tunnel, a slayer camp underground - this measures how far "
                                + "you can actually see and only draws that far, which is where the "
                                + "frames come back. Nothing is unloaded: every chunk stays in "
                                + "memory, so the full view returns instantly when you step out. It "
                                + "never draws less than the server itself would, and it does "
                                + "nothing at all above ground."),
                SettingRow.label(FarTerrainEnclosure.engaged()
                        ? "§bIndoors - drawing " + FarTerrainEnclosure.viewChunks()
                                + " ch instead of " + FarTerrainRenderDistance.current()
                                + " ch, nothing unloaded"
                        : "Only draw as far as you can see when you are underground"),

                SettingRow.rangeSlider("Distance Fog", 0, 100,
                        FarTerrainFog::fogPercent, FarTerrainFog::setFogPercent, "%")
                        .describe("How much distance haze is drawn over far terrain. 0% is none at "
                                + "all - remembered terrain keeps its own colours right to the "
                                + "horizon; 100% is untouched vanilla fog, which washes it pale "
                                + "toward the sky colour. Anything in between pushes the fog "
                                + "further out without removing it. Underwater, lava and blindness "
                                + "fog are never touched at any setting."),
                SettingRow.label(FarTerrainFog.fogPercent() <= 0
                        ? "§bNo distance fog - far terrain drawn at full colour"
                        : FarTerrainFog.fogPercent() >= 100
                                ? "Vanilla fog - lower this if far terrain looks washed out"
                                : "Fog pushed back, " + FarTerrainFog.fogPercent()
                                        + "% left - set 0% to remove it"),
                SettingRow.label("§8Past " + FarTerrainRenderDistance.VANILLA_MAX
                        + " ch it is remembered terrain, not live - no players, no fresh edits"),
                SettingRow.button("Delete This Map's Terrain",
                        () -> FarTerrainManager.getInstance().deleteCurrentMap())
                        .describe("Wipes the remembered terrain of the map you are currently on - do "
                                + "this after a Hypixel map update so stale terrain is not shown. On "
                                + "the shared map that clears every island on it at once. It rebuilds "
                                + "itself as you walk around."),
                SettingRow.button(deleteAllArmed()
                                ? "§cPress Again - Deletes EVERY Map"
                                : "Delete ALL Remembered Terrain",
                        FarTerrainModule::onDeleteAll)
                        .describe("Wipes every map's terrain and every island-claim record at once, "
                                + "not just the one you are standing on - the clean slate for "
                                + "re-capturing the world from scratch. Press twice to confirm. "
                                + "Nothing is lost that walking cannot rebuild, but that means "
                                + "walking every island again."),
                SettingRow.toggle("Neighbor Islands", () -> cfg().neighborView,
                        () -> { cfg().neighborView = !cfg().neighborView; save(); })
                        .describe("Allows islands to be drawn from each other's remembered "
                                + "terrain. Every island currently keeps to itself, so this "
                                + "changes nothing on its own: each island shows only what its "
                                + "own server sent, which is the one arrangement no neighbour "
                                + "can ever overwrite. Scenery from other islands, when it is "
                                + "enabled, is scenery only - waypoints, pathfinding and the map "
                                + "always use the real coordinates."),
                SettingRow.toggle("Unmeasured Islands Beside World", () -> cfg().unalignedBeside,
                        () -> { cfg().unalignedBeside = !cfg().unalignedBeside; save(); })
                        .describe("Islands whose real position has not been measured yet get an "
                                + "artificial spot beside the world instead of staying invisible. "
                                + "Off by default: a made-up position is exactly what looks like a "
                                + "wrong offset. Right now every supported island is measured, so "
                                + "this only matters when a future island has not been yet."),
                SettingRow.label("§8" + FarTerrainNeighborView.getInstance().statusLine()),
                SettingRow.holdKeybind("Mark Aimed Island", () -> cfg().markKey,
                        key -> {
                            cfg().markKey = key;
                            save();
                        })
                        .describe("Aim at any island and press this key: one line goes to chat and "
                                + "the log naming what you hit - which island it is, the exact "
                                + "block, and for a drawn neighbour island also the true position "
                                + "that scenery came from and which island claimed it. Mark the "
                                + "same landmark from both sides of a seam to measure how two "
                                + "islands should line up; a mark naming the wrong island exposes "
                                + "a mixed terrain file. A mouse button works too; the wheel does "
                                + "not, because this one watches for the key going down."),

                SettingRow.button("Log Island Overlap Report",
                        () -> {
                            var claims = FarTerrainManager.getInstance().claims();
                            if (claims == null) {
                                return;
                            }
                            claims.logReport();
                        })
                        .describe("Writes to the log which chunks each island on this map has "
                                + "claimed, their bounding boxes, and every position two islands "
                                + "both claim - i.e. where they are overwriting each other. Visit "
                                + "the islands you suspect and walk around them first; the record "
                                + "is kept on disk and builds up across sessions, so they do not "
                                + "have to be visited in one sitting."),
                SettingRow.label("§8" + overlapLine()),
                SettingRow.label(FarTerrainManager.getInstance().statusLine()),
                SettingRow.label("One file per map in config/sbs/render/"),
                SettingRow.label("§8Every island keeps its own terrain file"),
                SettingRow.label("§8Own map: " + String.join(", ", FarTerrainManager.SEPARATE_MAPS)));
    }
}
