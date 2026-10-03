/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.garden;

import net.minecraft.core.BlockPos;
import sbs.modid.client.core.build.logic.PlacementSnaps;
import sbs.modid.client.core.build.model.Schematic;
import sbs.modid.client.core.build.render.GhostModels;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.skills.garden.logic.GardenBlueprintManager;
import sbs.modid.client.skills.garden.logic.GardenPreset;
import sbs.modid.client.ui.settings.SettingRow;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.ModuleSubgroup;
import sbs.modid.client.core.module.SbsModule;

import java.util.List;

/**
 * Garden Blueprint module (Skills): copy the 96x96 plot you stand on, then rebuild it anywhere from a
 * transparent 3D ghost that marks each block right (green) or wrong (red) in real time.
 *
 * <p>Self-registered via {@code META-INF/services/sbs.modid.client.core.module.SbsModule}; all options
 * write to {@link SBSConfig.GardenBlueprintSettings} and are read live by the keybinds and the ghost
 * renderer, so every change applies without a restart. The plot is detected automatically from the
 * player's position on the fixed 96-grid – nothing is selected by hand.
 */
public final class GardenBlueprintModule implements SbsModule {

    static {
        // The plot-grid preset, offered to the shared hologram's nudge controls (Build Tools' "snap
        // to plot grid" key). Registered here because this class is loaded with the module list at
        // start-up, before any hologram can exist.
        PlacementSnaps.register(GardenPreset.PLOT_SNAP);
    }

    /** ServiceLoader needs a public no-arg constructor. */
    public GardenBlueprintModule() {
    }

    @Override
    public String id() {
        return "garden_blueprint";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.SKILLS;
    }

    @Override
    public ModuleSubgroup subgroup() {
        return ModuleSubgroup.FARMING_GARDEN;
    }

    @Override
    public String displayName() {
        return "Garden Blueprint";
    }

    @Override
    public String description() {
        return "Copy a Garden plot and rebuild it from a 3D ghost that marks each block right or wrong";
    }

    @Override
    public int accentColor() {
        return 0xFF8FD14D;
    }

    private static SBSConfig.GardenBlueprintSettings cfg() {
        return ConfigManager.getInstance().get().gardenBlueprint;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Garden Blueprint", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("Copy a Garden plot and rebuild it elsewhere from a see-through "
                                + "ghost: every block shows where it belongs, and mistakes are "
                                + "marked in their own color."),
                SettingRow.label("Copy the plot you stand on, then ghost it onto another plot"),

                SettingRow.keybind("Copy Plot Key", () -> cfg().copyKey,
                        key -> { cfg().copyKey = key; save(); })
                        .describe("Press while standing on a plot to copy it as the blueprint. "
                                + "The ghost preview turns on by itself."),
                SettingRow.keybind("Toggle Preview Key", () -> cfg().toggleKey,
                        key -> { cfg().toggleKey = key; save(); })
                        .describe("Press to show or hide the ghost preview without losing the "
                                + "copied blueprint."),
                SettingRow.label("Copying turns the ghost on automatically"),

                SettingRow.keybind("Place At Block Key", () -> cfg().placeKey,
                        key -> { cfg().placeKey = key; save(); })
                        .describe("Press to pin the copied blueprint to the block you are looking "
                                + "at (or the one under your feet): the ghost is drawn from there, "
                                + "so you can rebuild it anywhere, not only on a plot. Press again "
                                + "elsewhere to move it, or while sneaking to release it."),
                SettingRow.label("Pins the ghost to the block you look at; sneak-press releases it"),

                SettingRow.toggle("Only In Garden", () -> cfg().onlyInGarden,
                        () -> { cfg().onlyInGarden = !cfg().onlyInGarden; save(); })
                        .describe("Keeps the whole feature quiet outside the Garden."),
                SettingRow.intField("Capture Height", 1, 96,
                        () -> cfg().captureHeight,
                        value -> { cfg().captureHeight = value; save(); }, "")
                        .describe("How many blocks above your feet the copy reaches. Make it at "
                                + "least as tall as your farm's structures."),
                SettingRow.intField("Capture Depth", 0, 8,
                        () -> cfg().captureDepth,
                        value -> { cfg().captureDepth = value; save(); }, "")
                        .describe("How many blocks below your feet the copy reaches - for farms "
                                + "with water or soil layers under the walking level."),
                SettingRow.label("Blocks captured above / below your feet across the 96x96 plot"),

                SettingRow.intField("Render Radius", 4, 48,
                        () -> cfg().renderRadius,
                        value -> { cfg().renderRadius = value; save(); }, "")
                        .describe("Only ghost blocks within this many blocks of you are drawn. "
                                + "Lower it if a big blueprint costs FPS."),
                SettingRow.label("Lower this if a big farm costs FPS"),
                SettingRow.rangeSlider("Edge Opacity", 10, 100,
                        () -> cfg().opacity,
                        value -> { cfg().opacity = value; save(); }, "%")
                        .describe("How visible the ghost blocks' outlines are."),
                SettingRow.intField("Line Width", 1, 4,
                        () -> cfg().lineWidth,
                        value -> { cfg().lineWidth = value; save(); }, "px")
                        .describe("Thickness of those outlines in pixels."),
                SettingRow.toggle("Ghost Block Models", () -> cfg().ghostModels,
                        () -> { cfg().ghostModels = !cfg().ghostModels; save(); })
                        .describe("Draws the actual block that belongs in each spot as a "
                                + "see-through model, so you see WHAT to place, not just where."),
                SettingRow.label("Shows the real block that belongs there, see-through"),
                SettingRow.rangeSlider("Model Opacity", GhostModels.MIN_OPACITY, 100,
                        () -> cfg().modelOpacity,
                        value -> { cfg().modelOpacity = value; save(); }, "%")
                        .describe("How solid those ghost models look."),
                SettingRow.toggle("Model Over Wrong Blocks", () -> cfg().ghostModelsOnWrong,
                        () -> { cfg().ghostModelsOnWrong = !cfg().ghostModelsOnWrong; save(); })
                        .describe("Where you placed the wrong block, the correct one is ghosted "
                                + "on top of it too, so the fix is visible without checking the "
                                + "plan."),
                SettingRow.label("Ghosts the right block on top of a mistake as well"),

                SettingRow.toggle("Fill Ghost Blocks", () -> cfg().fillGhosts,
                        () -> { cfg().fillGhosts = !cfg().fillGhosts; save(); })
                        .describe("Fills ghost positions with flat color where no block model is "
                                + "drawn - cheaper to render than models."),
                SettingRow.label("Flat coloured fill, used where no block model is drawn"),
                SettingRow.rangeSlider("Fill Opacity", 5, 80,
                        () -> cfg().fillOpacity,
                        value -> { cfg().fillOpacity = value; save(); }, "%")
                        .describe("How solid that flat fill is."),
                SettingRow.toggle("Show Correct Blocks (green)", () -> cfg().showCorrect,
                        () -> { cfg().showCorrect = !cfg().showCorrect; save(); })
                        .describe("Also marks blocks that are already right (green). Useful to "
                                + "verify progress, noisy on a nearly finished build."),

                SettingRow.toggle("Custom Area Selection", () -> cfg().customArea,
                        () -> { cfg().customArea = !cfg().customArea; save(); })
                        .describe("Copy any box you select with two corners instead of the whole "
                                + "96x96 plot - for copying a single row or machine."),
                SettingRow.label("Pick any box with two corners instead of the plot, then copy it"),
                SettingRow.keybind("Set Corner Key", () -> cfg().cornerKey,
                        key -> { cfg().cornerKey = key; save(); })
                        .describe("Press to set a selection corner at the block you are looking "
                                + "at (or your feet). Presses alternate corner A and corner B."),
                SettingRow.label("Points at the block you look at (or your feet); presses alternate A / B"),

                SettingRow.text("Ghost Color Hex", "RRGGBB (to place)", 7,
                        () -> cfg().ghostColorHex,
                        value -> { cfg().ghostColorHex = value.trim(); save(); })
                        .describe("Color of blocks still to place, as a hex code."),
                SettingRow.text("Wrong Color Hex", "RRGGBB (mistake)", 7,
                        () -> cfg().errorColorHex,
                        value -> { cfg().errorColorHex = value.trim(); save(); })
                        .describe("Color marking a wrongly placed block, as a hex code."),
                SettingRow.text("Correct Color Hex", "RRGGBB (right)", 7,
                        () -> cfg().correctColorHex,
                        value -> { cfg().correctColorHex = value.trim(); save(); })
                        .describe("Color marking a correctly placed block, as a hex code."),
                SettingRow.text("Selection Color Hex", "RRGGBB (area box)", 7,
                        () -> cfg().selectionColorHex,
                        value -> { cfg().selectionColorHex = value.trim(); save(); })
                        .describe("Color of the custom-area selection box, as a hex code."),

                SettingRow.button("Save To Library", () -> GardenBlueprintManager.getInstance().saveToLibrary())
                        .describe("Saves the copied blueprint into the build library "
                                + "(config/sbs/schematics/) under its name and the date, so it is "
                                + "still there after a restart. Build Tools' Quick Paste and /.. load "
                                + "open it again. Never replaces an existing save."),
                SettingRow.button("Clear Blueprint", () -> GardenBlueprintManager.getInstance().clear())
                        .describe("Forgets the copied blueprint and removes the ghost. A copy saved to "
                                + "the library stays saved."),
                SettingRow.label(status()));
    }

    /** Whether a blueprint is stored, its size, source plot and where it is placed (a snapshot from
     *  page build time). */
    private static String status() {
        GardenBlueprintManager manager = GardenBlueprintManager.getInstance();
        Schematic blueprint = manager.blueprint();
        if (blueprint == null) {
            return "No blueprint copied yet";
        }
        BlockPos anchor = manager.anchor();
        return "Blueprint: " + blueprint.header().name() + "  •  "
                + blueprint.nonAirCount() + " blocks"
                + (anchor == null ? "" : "  •  placed at " + anchor.getX() + ", " + anchor.getY()
                        + ", " + anchor.getZ());
    }
}
