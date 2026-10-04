/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.experiment;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.helper.experiment.logic.ExperimentationTable;
import sbs.modid.client.ui.settings.SettingRow;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;

import java.util.List;

/**
 * Experimentation Table module (Skills): helpers for the three Enchanting-area minigames.
 *
 * <p>Self-registered via {@code META-INF/services/sbs.modid.client.core.module.SbsModule}; every option
 * writes to {@link SBSConfig.ExperimentationSettings} and is read live by {@link ExperimentationTable}
 * and its solvers, so changes apply without a restart.
 */
public final class ExperimentationModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public ExperimentationModule() {
    }

    @Override
    public String id() {
        return "experimentation";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.SKILLS;
    }

    @Override
    public String displayName() {
        return "Experimentation Table";
    }

    @Override
    public String description() {
        return "Solvers for Chronomatron, Ultrasequencer and Superpairs, plus a Guardian-pet reminder";
    }

    @Override
    public int accentColor() {
        return 0xFFB44DFF;
    }

    private static SBSConfig.ExperimentationSettings cfg() {
        return ConfigManager.getInstance().get().experimentation;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        List<SettingRow> rows = new java.util.ArrayList<>(tableSettings());
        rows.addAll(harpSettings());
        return rows;
    }

    private static List<SettingRow> tableSettings() {
        return List.of(
                SettingRow.toggle("Experimentation Table", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("Helpers for the three Experimentation Table minigames in the "
                                + "Enchanting area: they remember what the game showed you so you "
                                + "only have to click it back."),
                SettingRow.label("Helpers for the Enchanting-area minigames"),

                SettingRow.toggle("Chronomatron Helper", () -> cfg().chronomatron,
                        () -> { cfg().chronomatron = !cfg().chronomatron; save(); })
                        .describe("Remembers the color sequence Chronomatron flashes and "
                                + "highlights which tile to click next, in order."),
                SettingRow.label("Remembers the flashed colour sequence and highlights it back in order"),

                chronoColorRow("Chronomatron 1st Colour", 1, () -> cfg().chronomatronColor1,
                        v -> cfg().chronomatronColor1 = v, 0xFF55FF55)
                        .describe("Outline colour of the button to click next (and, during the "
                                + "show, of the first flash). Default green."),
                chronoColorRow("Chronomatron 2nd Colour", 2, () -> cfg().chronomatronColor2,
                        v -> cfg().chronomatronColor2 = v, 0xFFFFFF55)
                        .describe("Outline colour of the button after the next one. Default yellow."),
                chronoColorRow("Chronomatron 3rd+ Colour", 3, () -> cfg().chronomatronColor3,
                        v -> cfg().chronomatronColor3 = v, 0xFFFF5555)
                        .describe("Outline colour of the third button and every one after it. "
                                + "Default red."),

                SettingRow.toggle("Ultrasequencer Helper", () -> cfg().ultrasequencer,
                        () -> { cfg().ultrasequencer = !cfg().ultrasequencer; save(); })
                        .describe("Remembers the number on every Ultrasequencer tile while the "
                                + "round is shown, keeps it on the tile after the board hides it, "
                                + "and highlights the tiles in number order, the next one bright."),
                SettingRow.label("Keeps each tile's number after it is hidden and highlights the next one"),

                SettingRow.toggle("Start Cue", () -> cfg().startCue,
                        () -> { cfg().startCue = !cfg().startCue; save(); })
                        .describe("Chronomatron and Ultrasequencer show their hints only once you "
                                + "can click - while the game is still showing the pattern there is "
                                + "nothing to press. With this on, the moment the timer clock appears "
                                + "a \"GO\" shows in the title row and the board's frame flashes "
                                + "briefly. Default: on."),
                SettingRow.label("\"GO\" and a board flash when you can start clicking"),

                SettingRow.toggle("Ultra: Order Brightness", () -> cfg().ultrasequencerOrderGradient,
                        () -> { cfg().ultrasequencerOrderGradient = !cfg().ultrasequencerOrderGradient; save(); })
                        .describe("Shades Ultrasequencer tiles darker the further down the order "
                                + "they are, so the whole sequence is readable at a glance. Off, "
                                + "only the next tile is bright."),
                SettingRow.label("Shade tiles darker further down the order (off = next bright, rest dark green)"),

                SettingRow.toggle("Superpairs Helper", () -> cfg().superpairs,
                        () -> { cfg().superpairs = !cfg().superpairs; save(); })
                        .describe("Remembers every Superpairs card you have turned and shows it "
                                + "again after it flips back. Outlines in green the card that "
                                + "matches the one you just turned, or a pair you already know "
                                + "both halves of. Cards that share a name but differ (a different "
                                + "dye, another enchant) are never paired. Shows \"wait\" while "
                                + "the board is resetting after a miss. Never blocks a click."),
                SettingRow.label("Shows turned cards again and outlines known pairs"),

                SettingRow.toggle("Block Misclicks", () -> cfg().blockMisclicks,
                        () -> { cfg().blockMisclicks = !cfg().blockMisclicks; save(); })
                        .describe("Swallows clicks on the wrong tile in Chronomatron and "
                                + "Ultrasequencer, so a slip of the mouse cannot end the round. "
                                + "It stands down the moment it is unsure: two refused clicks in "
                                + "a row, or a board it has lost track of, and it stops blocking "
                                + "for the rest of the round so you can always finish by hand. "
                                + "Never active in Superpairs. Default: off."),
                SettingRow.label("Stops out-of-order clicks in Chronomatron / Ultrasequencer (never Superpairs)"),
                SettingRow.label("Gives up after two refusals in a row - it never blocks you out"),

                SettingRow.toggle("Guardian Pet Alert", () -> cfg().guardianPetAlert,
                        () -> { cfg().guardianPetAlert = !cfg().guardianPetAlert; save(); })
                        .describe("Warns you in chat when you open the table without a Guardian "
                                + "pet equipped - the pet adds bonus XP, and forgetting the swap "
                                + "wastes the attempt."),
                SettingRow.label("Warns in chat when you open the table without a Guardian pet"));
    }

    /** Melody's Harp (The Park, Melody's Plateau). Display only. */
    private static List<SettingRow> harpSettings() {
        List<SettingRow> rows = new java.util.ArrayList<>();
        rows.add(SettingRow.label("— Melody's Harp —"));
        rows.add(SettingRow.toggle("Melody's Harp Helper", () -> cfg().harp,
                        () -> { cfg().harp = !cfg().harp; save(); })
                .describe("Lights the lane to click in Melody's Harp a little before the note "
                        + "arrives - early by your ping plus your reaction time, so the click lands "
                        + "on time - and outlines the notes still coming down. It only shows; it "
                        + "never clicks or holds back a click for you."));
        rows.add(SettingRow.segmented("Ping For The Lead", List.of("Auto", "Manual"),
                        () -> cfg().harpPingAuto ? 0 : 1,
                        i -> { cfg().harpPingAuto = i == 0; save(); })
                .describe("Auto measures your ping while the Harp is open and uses the average of "
                        + "the last 10 readings. Manual uses the value below."));
        rows.add(SettingRow.intField("Manual Ping", 0, 1000, () -> cfg().harpManualPingMs,
                        v -> { cfg().harpManualPingMs = v; save(); }, " ms")
                .describe("Your ping in milliseconds, used only when the lead is set to Manual."));
        rows.add(SettingRow.rangeSlider("Reaction Time", 100, 500, () -> cfg().harpReactionMs,
                        v -> { cfg().harpReactionMs = v; save(); }, " ms")
                .describe("Added to the ping to get the lead. 250 ms is about the average simple "
                        + "visual reaction time of a young adult - an ESTIMATE, not measured on "
                        + "you. Lower it if the cue feels late, raise it if early."));
        rows.add(SettingRow.color("Harp Highlight Colour", () -> cfg().harpColorHex, () -> 0xFF5DE0A0,
                        () -> openPicker())
                .describe("The colour of the cue and of the incoming notes' outlines."));
        rows.add(SettingRow.toggle("Flash \"NOW\"", () -> cfg().harpFlash,
                        () -> { cfg().harpFlash = !cfg().harpFlash; save(); })
                .describe("Writes NOW on the lit slot while the cue is on."));
        return rows;
    }

    /**
     * A colour row over an ARGB int field: the shared colour row and picker speak RRGGBB hex, so
     * the int is shown as hex and a picked hex is stored opaque; clearing it restores the default.
     */
    private static SettingRow chronoColorRow(String label, int position, java.util.function.IntSupplier get,
                                             java.util.function.IntConsumer set, int fallback) {
        java.util.function.Supplier<String> hex =
                () -> String.format(java.util.Locale.ROOT, "%06X", get.getAsInt() & 0xFFFFFF);
        return SettingRow.color(label, hex, () -> fallback, () -> {
            net.minecraft.client.gui.screens.Screen previous =
                    sbs.modid.client.core.api.GuiStateManager.getInstance().getCurrentScreen();
            net.minecraft.client.Minecraft.getInstance().setScreenAndShow(
                    new sbs.modid.client.ui.theme.ThemeColorPickerScreen("Chronomatron  •  " + position
                            + (position >= 3 ? "+" : "") + ". button", hex.get(), value -> {
                                set.accept(parseArgb(value, fallback));
                                save();
                            }, previous));
        });
    }

    /** RRGGBB (optionally with a leading '#') to opaque ARGB, or {@code fallback} when blank/invalid. */
    static int parseArgb(String hex, int fallback) {
        if (hex == null) {
            return fallback;
        }
        String s = hex.trim().replace("#", "");
        if (!s.matches("[0-9A-Fa-f]{6}")) {
            return fallback;
        }
        return 0xFF000000 | Integer.parseInt(s, 16);
    }

    private static void openPicker() {
        net.minecraft.client.gui.screens.Screen previous =
                sbs.modid.client.core.api.GuiStateManager.getInstance().getCurrentScreen();
        net.minecraft.client.Minecraft.getInstance().setScreenAndShow(
                new sbs.modid.client.ui.theme.ThemeColorPickerScreen("Melody's Harp  •  Highlight",
                        cfg().harpColorHex, value -> {
                            cfg().harpColorHex = value == null ? "" : value;
                            save();
                        }, previous));
    }
}
