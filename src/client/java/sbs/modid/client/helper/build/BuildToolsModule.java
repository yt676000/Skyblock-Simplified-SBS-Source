/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.build;

import sbs.modid.client.core.build.render.GhostModels;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.List;

/**
 * Build Tools (Quality of Life): a selection stick, {@code /..} commands, a library of saved builds
 * and see-through holograms to build along with.
 *
 * <p><b>Why it is legitimate on a server</b> (recorded here as AGENTS.md asks): on Hypixel the feature
 * only reads blocks the client already has loaded and draws - a copy, a hologram, a guide. It never
 * places, breaks or clicks a block and never sends a command; every verb that changes the world is
 * refused off singleplayer ({@code BuildGate}). World edits run only against the integrated server of
 * a singleplayer world, where there is nobody to have an advantage over.
 *
 * <p>Self-registered via {@code META-INF/services}; every option writes to
 * {@link SBSConfig.BuildToolsSettings} and applies at once.
 */
public final class BuildToolsModule implements SbsModule {

    static {
        // Wiring that must exist before the first key press, done once as the module list loads:
        // Enter on a placed paste applies it in singleplayer, //timeline opens its screen, and the
        // preview/progress states register their keys and HUD lines.
        sbs.modid.client.helper.build.logic.Placement.setApplier(
                sbs.modid.client.helper.build.command.BuildEdits::applyPaste);
        sbs.modid.client.helper.build.command.BuildCommands.setTimelineOpener(() ->
                net.minecraft.client.Minecraft.getInstance().setScreenAndShow(
                        new sbs.modid.client.helper.build.ui.TimelineScreen()));
        Runnable quickPaste = () -> net.minecraft.client.Minecraft.getInstance().setScreenAndShow(
                new sbs.modid.client.helper.build.ui.QuickPasteScreen());
        sbs.modid.client.helper.build.command.BuildCommands.setQuickPasteOpener(quickPaste);
        sbs.modid.client.helper.build.command.BuildCommands.setLibraryOpener(() ->
                net.minecraft.client.Minecraft.getInstance().setScreenAndShow(
                        new sbs.modid.client.helper.build.ui.BuildLibraryScreen()));
        sbs.modid.client.helper.build.command.BuildKeybinds.setQuickPaste(quickPaste);
        sbs.modid.client.helper.build.logic.PendingEdit.active();
        sbs.modid.client.helper.build.logic.BuildGuide.active();
        sbs.modid.client.helper.build.logic.Freecam.active();
    }

    /** ServiceLoader needs a public no-arg constructor. */
    public BuildToolsModule() {
    }

    @Override
    public String id() {
        return "build_tools";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.QUALITY_OF_LIFE;
    }

    @Override
    public String displayName() {
        return "Build Tools";
    }

    @Override
    public String description() {
        return "Select, copy and save builds, paste them as holograms; edit worlds in singleplayer";
    }

    @Override
    public int accentColor() {
        return 0xFFE0B040;
    }

    private static SBSConfig.BuildToolsSettings cfg() {
        return ConfigManager.getInstance().get().buildTools;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Build Tools", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("Turns on the //commands, the corner keys, the Magic Stick Thingy and "
                                + "the build hologram. On a server it only copies and shows builds - it "
                                + "never places or breaks a block. Off by default."),
                SettingRow.label("Type //help in chat for every command"),

                SettingRow.keybind("Corner 1 At Look Key", () -> cfg().corner1Key,
                        key -> { cfg().corner1Key = key; save(); })
                        .describe("Sets selection corner 1 at the block you look at (or your feet). "
                                + "This is how you select on a server, where the stick cannot be given. "
                                + "Unbound by default."),
                SettingRow.keybind("Corner 2 At Look Key", () -> cfg().corner2Key,
                        key -> { cfg().corner2Key = key; save(); })
                        .describe("Sets selection corner 2 at the block you look at. Unbound by default."),
                SettingRow.label("Singleplayer edits"),
                SettingRow.toggle("Preview Edits", () -> cfg().previewEdits,
                        () -> { cfg().previewEdits = !cfg().previewEdits; save(); })
                        .describe("Shows what an edit will do as a hologram first - green added, red "
                                + "removed - and waits for Enter (Esc cancels). Adding ! to a command "
                                + "skips it once. On by default."),
                SettingRow.intField("Blocks Per Tick", 1000, 100000, () -> cfg().blocksPerTick,
                        value -> { cfg().blocksPerTick = value; save(); }, "")
                        .describe("How many blocks a singleplayer edit changes per game tick. Higher "
                                + "finishes sooner but can stutter. Default 20000."),
                SettingRow.keybind("Quick Paste Key", () -> cfg().quickPasteKey,
                        key -> { cfg().quickPasteKey = key; save(); })
                        .describe("Opens the grid of your saved builds: click one to place it as a "
                                + "hologram at your look point. Also //quick. Unbound by default."),
                SettingRow.toggle("Live Selection Preview", () -> cfg().livePreview,
                        () -> { cfg().livePreview = !cfg().livePreview; save(); })
                        .describe("Holding the Magic Stick Thingy after corner 1, the box to the block "
                                + "you look at is drawn live, with its size, until you click corner 2 - "
                                + "then it locks in the selection colour. On by default."),
                SettingRow.color("Live Preview Color", () -> cfg().previewColorHex, () -> 0xFF7FD4FF,
                        () -> openPicker("Live Preview", () -> cfg().previewColorHex,
                                hex -> cfg().previewColorHex = hex))
                        .describe("Colour of the live box and of a hologram's outline. Default light blue."),
                SettingRow.rangeSlider("Box Fill Opacity", 0, 60, () -> cfg().previewOpacity,
                        value -> { cfg().previewOpacity = value; save(); }, "%")
                        .describe("How strongly the selection and hologram boxes tint their faces; the "
                                + "outline always stays solid. Keep it low so the blocks behind show. "
                                + "Default 18%."),
                SettingRow.toggle("Show Targeted Block", () -> cfg().showTargetedBlock,
                        () -> { cfg().showTargetedBlock = !cfg().showTargetedBlock; save(); })
                        .describe("While you hold the Magic Stick Thingy, a corner key, or fly in freecam, "
                                + "the block you would select is outlined with its name and position. "
                                + "Alt+wheel picks the block behind it. On by default."),
                SettingRow.intField("Target Ray Distance", 8, 128, () -> cfg().rayDistance,
                        value -> { cfg().rayDistance = value; save(); }, "")
                        .describe("How far the freecam ray reaches for the stick and //pos1 //pos2. Default 64."),

                SettingRow.label("Freecam - the camera flies, you stay; clicks only select"),
                SettingRow.keybind("Freecam Key", () -> cfg().freecamKey,
                        key -> { cfg().freecamKey = key; save(); })
                        .describe("Toggles freecam (also //freecam). The camera leaves your eyes and flies "
                                + "with WASD, space and sneak; your character stays put and nothing is sent "
                                + "for the camera. Clicks only set selection corners. Unbound by default."),
                SettingRow.toggle("Freecam Noclip", () -> cfg().freecamNoclip,
                        () -> { cfg().freecamNoclip = !cfg().freecamNoclip; save(); })
                        .describe("The camera flies through blocks. Off: it stops at them. On by default."),
                SettingRow.toggle("Freecam On Servers", () -> cfg().freecamMultiplayer,
                        () -> {
                            cfg().freecamMultiplayer = !cfg().freecamMultiplayer;
                            if (cfg().freecamMultiplayer && !cfg().freecamWarned) {
                                cfg().freecamWarned = true;
                                sbs.modid.client.helper.build.logic.BuildChat.warn("Freecam on servers is at your own "
                                        + "risk: seeing into closed areas can count as an unfair advantage under "
                                        + "the server's rules.");
                            }
                            save();
                        })
                        .describe("Allows freecam on servers - only on your own Private Island and on Gardens "
                                + "(yours, or one you are visiting, to copy a farm design); not on someone "
                                + "else's island, nowhere else. Leaving those places turns it off at once. "
                                + "At your own risk: "
                                + "seeing into closed areas can count as an unfair advantage under the server's "
                                + "rules. Off by default."),
                SettingRow.intField("Freecam Range On Servers", 4, 128, () -> cfg().freecamRange,
                        value -> { cfg().freecamRange = value; save(); }, "")
                        .describe("On servers the camera stays within this many blocks of you. Default 32."),
                SettingRow.intField("Freecam Range Singleplayer", 0, 512, () -> cfg().freecamRangeSingleplayer,
                        value -> { cfg().freecamRangeSingleplayer = value; save(); }, "")
                        .describe("An optional limit in singleplayer; 0 means none. Default 0."),
                SettingRow.toggle("Freecam Hides Entities On Servers", () -> cfg().freecamHideEntities,
                        () -> { cfg().freecamHideEntities = !cfg().freecamHideEntities; save(); })
                        .describe("On servers, players, mobs, armor stands and dropped items are not drawn from "
                                + "the freecam view - only blocks - so it cannot be used to scout. On by default."),
                SettingRow.toggle("Hide Selection Unless Holding Stick", () -> cfg().hideSelectionUnlessHeld,
                        () -> { cfg().hideSelectionUnlessHeld = !cfg().hideSelectionUnlessHeld; save(); })
                        .describe("The selection box and its label are drawn only while you hold the Magic Stick "
                                + "Thingy, or for 10 seconds after a build key or command. The selection itself is "
                                + "kept - //copy still works. On by default."),
                SettingRow.keybind("Clear Selection Key", () -> cfg().clearSelectionKey,
                        key -> { cfg().clearSelectionKey = key; save(); })
                        .describe("Clears the selection - only while you hold the Magic Stick Thingy, so it never "
                                + "takes the key anywhere else. Sneak + right-click on air does the same. Default "
                                + "Backspace."),
                SettingRow.toggle("Build Tools Help Card", () -> cfg().helpCard,
                        () -> { cfg().helpCard = !cfg().helpCard; save(); })
                        .describe("A card with the commands and keys for what you are doing: while you hold the "
                                + "stick, have a selection, or place a hologram. Movable in the GUI editor. On by "
                                + "default."),
                SettingRow.toggle("Compact Help Card", () -> cfg().helpCompact,
                        () -> { cfg().helpCompact = !cfg().helpCompact; save(); })
                        .describe("The help card as one line of the most useful keys. Off by default."),
                SettingRow.keybind("Build Library Key", () -> cfg().libraryKey,
                        key -> { cfg().libraryKey = key; save(); })
                        .describe("Opens the Build Library: your saved builds with a large preview and every "
                                + "action as a button. Also //library. Unbound by default."),
                SettingRow.button("Open Build Library", () -> net.minecraft.client.Minecraft.getInstance()
                                .setScreenAndShow(new sbs.modid.client.helper.build.ui.BuildLibraryScreen()))
                        .describe("Rename, duplicate, delete, share and preview your saved builds."),
                SettingRow.toggle("Show Selection Size", () -> cfg().sizeLabel,
                        () -> { cfg().sizeLabel = !cfg().sizeLabel; save(); })
                        .describe("Writes the selection's size and block count over its box. On by default."),

                SettingRow.label("Hologram look"),
                SettingRow.intField("Render Radius", 4, 64, () -> cfg().renderRadius,
                        value -> { cfg().renderRadius = value; save(); }, "")
                        .describe("Only hologram blocks within this many blocks of you are drawn; the "
                                + "outline of the whole build is always shown. Lower it if a big build "
                                + "costs FPS. Default 24."),
                SettingRow.toggle("Ghost Block Models", () -> cfg().ghostModels,
                        () -> { cfg().ghostModels = !cfg().ghostModels; save(); })
                        .describe("Draws the block that belongs in each spot as a see-through model, "
                                + "so you see what to place, not just where. On by default."),
                SettingRow.rangeSlider("Model Opacity", GhostModels.MIN_OPACITY, 100,
                        () -> cfg().modelOpacity, value -> { cfg().modelOpacity = value; save(); }, "%")
                        .describe("How solid those models look. Default 55%."),
                SettingRow.rangeSlider("Edge Opacity", 10, 100, () -> cfg().edgeOpacity,
                        value -> { cfg().edgeOpacity = value; save(); }, "%")
                        .describe("How visible the block outlines are. Default 70%."),
                SettingRow.intField("Line Width", 1, 4, () -> cfg().lineWidth,
                        value -> { cfg().lineWidth = value; save(); }, "px")
                        .describe("Outline thickness in pixels. Default 2."),
                SettingRow.toggle("Fill Without Model", () -> cfg().fillGhosts,
                        () -> { cfg().fillGhosts = !cfg().fillGhosts; save(); })
                        .describe("Flat colour in spots that get no block model - cheaper to draw. "
                                + "On by default."),
                SettingRow.rangeSlider("Fill Opacity", 5, 80, () -> cfg().fillOpacity,
                        value -> { cfg().fillOpacity = value; save(); }, "%")
                        .describe("How solid that flat fill is. Default 25%."),
                SettingRow.toggle("Show Matching Blocks", () -> cfg().showCorrect,
                        () -> { cfg().showCorrect = !cfg().showCorrect; save(); })
                        .describe("Also outlines blocks that already match the build (green). Handy to "
                                + "check progress, noisy near the end. Off by default."),

                SettingRow.color("Selection Color", () -> cfg().selectionColorHex, () -> 0xFFFFE24B,
                        () -> openPicker("Selection", () -> cfg().selectionColorHex,
                                hex -> cfg().selectionColorHex = hex))
                        .describe("The selection box and its size label. Default yellow."),
                SettingRow.color("To Place Color", () -> cfg().ghostColorHex, () -> 0xFF3FB4FF,
                        () -> openPicker("To Place", () -> cfg().ghostColorHex,
                                hex -> cfg().ghostColorHex = hex))
                        .describe("Hologram blocks still to place. Default blue."),
                SettingRow.color("Overwrite Color", () -> cfg().collisionColorHex, () -> 0xFFFF2020,
                        () -> openPicker("Overwrite", () -> cfg().collisionColorHex,
                                hex -> cfg().collisionColorHex = hex))
                        .describe("Blocks a paste would overwrite, and wrong blocks while building "
                                + "along. Default red."),
                SettingRow.color("Matching Color", () -> cfg().correctColorHex, () -> 0xFF30E030,
                        () -> openPicker("Matching", () -> cfg().correctColorHex,
                                hex -> cfg().correctColorHex = hex))
                        .describe("Blocks that already match. Default green."),
                SettingRow.color("Edit Adds Color", () -> cfg().addedColorHex, () -> 0xFF30E030,
                        () -> openPicker("Edit Adds", () -> cfg().addedColorHex,
                                hex -> cfg().addedColorHex = hex))
                        .describe("In an edit's preview: blocks it adds or changes. Default green."),
                SettingRow.color("Edit Removes Color", () -> cfg().removedColorHex, () -> 0xFFFF2020,
                        () -> openPicker("Edit Removes", () -> cfg().removedColorHex,
                                hex -> cfg().removedColorHex = hex))
                        .describe("In an edit's preview: blocks it removes. Default red."));
    }

    private static void openPicker(String label, java.util.function.Supplier<String> current,
                                   java.util.function.Consumer<String> setter) {
        net.minecraft.client.gui.screens.Screen previous =
                sbs.modid.client.core.api.GuiStateManager.getInstance().getCurrentScreen();
        net.minecraft.client.Minecraft.getInstance().setScreenAndShow(
                new sbs.modid.client.ui.theme.ThemeColorPickerScreen(
                        "Build Tools  •  " + label, current.get(),
                        value -> {
                            setter.accept(value == null ? "" : value);
                            save();
                        }, previous));
    }
}
