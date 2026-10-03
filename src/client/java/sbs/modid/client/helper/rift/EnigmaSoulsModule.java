/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.rift;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.ModuleSubgroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.helper.rift.logic.EnigmaSoulDatabase;
import sbs.modid.client.helper.rift.logic.EnigmaSoulStore;
import sbs.modid.client.helper.rift.logic.EnigmaSoulTracker;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.ArrayList;
import java.util.List;

/**
 * Enigma Souls module: waypoints for the Rift's souls, in the three states they can be in.
 *
 * <p>Self-registered via {@code META-INF/services/sbs.modid.client.core.module.SbsModule}; settings in
 * {@link SBSConfig.EnigmaSoulSettings}, coordinates in {@link EnigmaSoulDatabase}, per-profile
 * progress in {@link EnigmaSoulStore}.
 */
public final class EnigmaSoulsModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public EnigmaSoulsModule() {
    }

    @Override
    public String id() {
        return "enigma_souls";
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
        return "Enigma Souls";
    }

    @Override
    public String description() {
        return "Marks the Rift's Enigma Souls, with what each one actually needs";
    }

    @Override
    public int accentColor() {
        return 0xFF6FD8FF;
    }

    private static SBSConfig.EnigmaSoulSettings cfg() {
        return ConfigManager.getInstance().get().enigmaSouls;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        List<SettingRow> rows = new ArrayList<>(List.of(
                SettingRow.toggle("Enigma Souls", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("Marks every catalogued Enigma Soul in the Rift. Only draws "
                                + "inside the Rift, and records nothing while off."),
                SettingRow.label("Rift only - the markers do not exist anywhere else"),

                SettingRow.toggle("Show Instructions", () -> cfg().showInstructions,
                        () -> { cfg().showInstructions = !cfg().showInstructions; save(); })
                        .describe("Puts how to actually collect the nearest soul under its marker. "
                                + "Most Enigma Souls are not a right-click on an orb - they are two "
                                + "players on plates, three minutes in a chair, fifty hot dogs - so "
                                + "a coordinate on its own sends you to stare at an empty wall. "
                                + "Only the nearest one gets the line; forty of them at once would "
                                + "bury the field."),

                SettingRow.toggle("Show Unknown", () -> cfg().showUnknown,
                        () -> { cfg().showUnknown = !cfg().showUnknown; save(); })
                        .describe("Draws the souls that Hypixel's count says have been found by "
                                + "somebody, but that this client never witnessed. On an "
                                + "established profile that is most of them. They are drawn in "
                                + "their own colour and never as missing - turning this off leaves "
                                + "only the ones known to still be out there."),
                SettingRow.toggle("Show Found", () -> cfg().showFound,
                        () -> { cfg().showFound = !cfg().showFound; save(); })
                        .describe("Keeps drawing souls already on record as found, dimmed. Off by "
                                + "default: a soul you have collected disappearing is the only way "
                                + "the field ever empties out."),

                SettingRow.toggle("Hide Two-Player Souls", () -> cfg().hideMultiplayer,
                        () -> { cfg().hideMultiplayer = !cfg().hideMultiplayer; save(); })
                        .describe("Removes the souls that cannot be collected alone. Not 'hard' - "
                                + "impossible: without a second player on the plates that marker "
                                + "can never go away, and a permanent marker is noise."),
                SettingRow.toggle("Hide Purchased Souls", () -> cfg().hidePurchases,
                        () -> { cfg().hidePurchases = !cfg().hidePurchases; save(); })
                        .describe("Removes the souls that are bought from an NPC for motes and "
                                + "materials rather than found. Nothing to walk to, so some players "
                                + "would rather they were not on the map."),

                SettingRow.toggle("Pointer Lines", () -> cfg().showTracers,
                        () -> { cfg().showTracers = !cfg().showTracers; save(); })
                        .anchor("tracers")
                        .describe("Draws a line from your crosshair to each soul not yet found."),
                SettingRow.toggle("Through Walls", () -> cfg().throughWalls,
                        () -> { cfg().throughWalls = !cfg().throughWalls; save(); })
                        .describe("Keeps markers visible with terrain in the way."),
                SettingRow.toggle("Show Distance", () -> cfg().showDistance,
                        () -> { cfg().showDistance = !cfg().showDistance; save(); })
                        .describe("Puts the distance in each marker's label."),
                SettingRow.toggle("Show Soul Ids", () -> cfg().showSoulIds,
                        () -> { cfg().showSoulIds = !cfg().showSoulIds; save(); })
                        .describe("Puts the data-file id in the label. For correcting coordinates "
                                + "and reporting a wrong one; off by default."),
                SettingRow.toggle("Report Unmatched", () -> cfg().reportUnmatched,
                        () -> { cfg().reportUnmatched = !cfg().reportUnmatched; save(); })
                        .describe("Says so in chat when a soul was collected but no single "
                                + "catalogued coordinate was in reach - which is how a missing or "
                                + "wrong coordinate gets noticed."),

                SettingRow.label("§8—— Colours ——"),
                SettingRow.enumOptions("Missing Colour", () -> cfg().missingColor,
                        value -> {
                            cfg().missingColor = value;
                            cfg().missingColorHex = "";
                            save();
                        }, v -> v.displayName()),
                SettingRow.rangeSlider("Missing Opacity", 10, 100, () -> cfg().missingOpacity,
                        value -> { cfg().missingOpacity = value; save(); }, "%"),
                SettingRow.enumOptions("Unknown Colour", () -> cfg().unknownColor,
                        value -> {
                            cfg().unknownColor = value;
                            cfg().unknownColorHex = "";
                            save();
                        }, v -> v.displayName()),
                SettingRow.rangeSlider("Unknown Opacity", 10, 100, () -> cfg().unknownOpacity,
                        value -> { cfg().unknownOpacity = value; save(); }, "%"),
                SettingRow.enumOptions("Found Colour", () -> cfg().foundColor,
                        value -> {
                            cfg().foundColor = value;
                            cfg().foundColorHex = "";
                            save();
                        }, v -> v.displayName()),
                SettingRow.rangeSlider("Found Opacity", 0, 100, () -> cfg().foundOpacity,
                        value -> { cfg().foundOpacity = value; save(); }, "%"),
                SettingRow.enumOptions("Pointer Line Colour", () -> cfg().tracerColor,
                        value -> {
                            cfg().tracerColor = value;
                            cfg().tracerColorHex = "";
                            save();
                        }, v -> v.displayName())
                        .anchor("tracer_colour"),
                SettingRow.rangeSlider("Pointer Line Opacity", 10, 100, () -> cfg().tracerOpacity,
                        value -> { cfg().tracerOpacity = value; save(); }, "%")
                        .anchor("tracer_opacity")));

        // ---------------------------------------------------------------- record state
        rows.add(SettingRow.label("§8—— Collection record ——"));
        rows.add(SettingRow.label("§8" + EnigmaSoulTracker.getInstance().reconciliation()));
        int uncatalogued = EnigmaSoulTracker.getInstance().uncataloguedCount();
        if (uncatalogued > 0) {
            rows.add(SettingRow.label("§e" + uncatalogued + " soul(s) exist that the data file "
                    + "does not have coordinates for yet."));
        }
        rows.add(SettingRow.button("Forget Collection Record",
                () -> EnigmaSoulStore.getInstance().reset())
                .describe("Throws away everything recorded for this profile - the souls this client "
                        + "witnessed and the last count Hypixel stated. Collect one soul and the "
                        + "count comes straight back."));
        rows.add(SettingRow.label("§8Data: " + EnigmaSoulDatabase.status()));
        rows.add(SettingRow.label("§8A soul is only recorded when exactly one catalogued "
                + "coordinate is in reach - never on a guess."));
        return List.copyOf(rows);
    }
}
