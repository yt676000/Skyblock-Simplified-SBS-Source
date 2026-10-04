/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.metaldetector;

import sbs.modid.client.core.alert.AlertChannelRows;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.ui.HudEditorScreen;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.ArrayList;
import java.util.List;

/**
 * Metal Detector module (Skills): works out where the buried treasure is in the Mines of Divan from
 * the distance the detector reports, and marks the spot. Also the Divan Tools checklist: the four
 * scavenged tools the detector digs up, which Keeper each goes back to, and the Jade Crystal at the
 * end - read from the Keepers' chat lines.
 *
 * <p>Read-only by design - a waypoint, an outline and a card, no walking, no digging, no aiming.
 * Self-registered via {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 *
 * <p>Ships <b>off</b>: the action-bar wording the solver reads is unverified, so it may not fire at
 * all yet, and the checklist has not been watched through a whole run. See
 * {@code docs/features/metal-detector.md}.
 */
public final class MetalDetectorModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public MetalDetectorModule() {
    }

    @Override
    public String id() {
        return "metal_detector";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.SKILLS;
    }

    @Override
    public String displayName() {
        return "Metal Detector";
    }

    @Override
    public String description() {
        return "Works out where the Mines of Divan treasure is from the detector's distance";
    }

    @Override
    public int accentColor() {
        return 0xFF49C7B0;
    }

    private static SBSConfig.MetalDetectorSettings cfg() {
        return ConfigManager.getInstance().get().metalDetector;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        List<SettingRow> rows = new ArrayList<>(List.of(
                SettingRow.toggle("Metal Detector Solver", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("In the Mines of Divan, reads the distance the Metal Detector "
                                + "puts on your action bar and works out where the treasure is "
                                + "from several readings taken in different places. It marks the "
                                + "spot and nothing else - it will not walk or dig for you."),
                SettingRow.label("Take readings from a few spots - walking a straight line is not enough"),
                SettingRow.label("Off until the action bar wording is confirmed in game"),

                SettingRow.toggle("Treasure Waypoint", () -> cfg().waypoint,
                        () -> { cfg().waypoint = !cfg().waypoint; save(); })
                        .describe("Marks the solved spot as a waypoint, with the beam and distance "
                                + "every other SBS waypoint has."),
                SettingRow.label("Cleared automatically on a find, a new lobby or leaving the zone"),

                SettingRow.toggle("Treasure Box", () -> cfg().box,
                        () -> { cfg().box = !cfg().box; save(); })
                        .describe("Outlines the solved block in the world. When the readings were "
                                + "all taken at one height the depth is an estimate, and the "
                                + "outline says so rather than pretending to be exact."),

                SettingRow.toggle("Divan Tools Checklist", () -> cfg().checklist,
                        () -> { cfg().checklist = !cfg().checklist; save(); })
                        .describe("A card in the Crystal Hollows with the four scavenged tools - "
                                + "Golden Hammer, Emerald Hammer, Diamond Axe, Lapis Sword - each "
                                + "missing, found or returned to its Keeper, then the Jade Crystal "
                                + "once all four are back. Shown in the Mines of Divan, and "
                                + "elsewhere on the island while a run is open. Default: off."),
                SettingRow.label("Per lobby - a new lobby starts the list again")));
        rows.addAll(AlertChannelRows.forAlert("divan_tool", "you dig up a scavenged tool",
                () -> cfg().toolAlertChannels, mask -> { cfg().toolAlertChannels = mask; save(); }));
        rows.add(SettingRow.button("Move / Resize Divan Tools Card", () -> net.minecraft.client.Minecraft
                        .getInstance().setScreenAndShow(new HudEditorScreen(
                                new HudElement[] {HudElement.DIVAN_CHECKLIST}, "Edit Divan Tools Card")))
                .describe("Opens the editor where you drag the card anywhere on the screen."));
        return rows;
    }
}
