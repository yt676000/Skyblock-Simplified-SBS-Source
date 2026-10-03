/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.fallenstar;

import sbs.modid.client.core.alert.AlertChannelRows;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.ModuleSubgroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.ui.HudEditorScreen;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.ArrayList;
import java.util.List;

/**
 * Fallen Star (Skills > Mining): an alert and an approximate waypoint when a Fallen Star crashes in the
 * Dwarven Mines, a HUD line while it is up, and the Cult of the Fallen Star line the event calendar
 * feeds. Self-registered via {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 */
public final class FallenStarModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public FallenStarModule() {
    }

    @Override
    public String id() {
        return "fallen_star";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.SKILLS;
    }

    @Override
    public ModuleSubgroup subgroup() {
        return ModuleSubgroup.MINING;
    }

    @Override
    public String displayName() {
        return "Cult of the Fallen Star";
    }

    @Override
    public String description() {
        return "Tells you where a Fallen Star crashed in the Dwarven Mines and how long ago";
    }

    @Override
    public int accentColor() {
        return 0xFFB58CFF;
    }

    private static SBSConfig.FallenStarSettings cfg() {
        return ConfigManager.getInstance().get().fallenStar;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        List<SettingRow> rows = new ArrayList<>();
        rows.add(SettingRow.toggle("Fallen Star Helper", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                .describe("When a Fallen Star crashes in your Dwarven Mines lobby, tells you which "
                        + "zone it landed in. Default: on."));
        rows.addAll(AlertChannelRows.forAlert("fallen_star", "a Fallen Star crashes",
                () -> cfg().alertChannels, mask -> { cfg().alertChannels = mask; save(); }));
        rows.add(SettingRow.toggle("Fallen Star Waypoint", () -> cfg().waypoint,
                        () -> { cfg().waypoint = !cfg().waypoint; save(); })
                .describe("Puts a waypoint on the zone the star crashed in. It points at the zone, "
                        + "not the star itself - the game only names the zone. Default: on."));
        rows.add(SettingRow.toggle("Fallen Star HUD", () -> cfg().hud,
                        () -> { cfg().hud = !cfg().hud; save(); })
                .describe("A small line with the zone and how long ago the star crashed, and the "
                        + "Cult of the Fallen Star while it is on. Default: on."));
        rows.add(SettingRow.label("§8Cleared after 30 minutes - the end is never announced"));
        rows.add(SettingRow.button("Move / Resize Fallen Star Line", () -> net.minecraft.client.Minecraft
                        .getInstance().setScreenAndShow(new HudEditorScreen(
                                new HudElement[] {HudElement.FALLEN_STAR}, "Edit Fallen Star Line")))
                .describe("Opens the editor where you drag the line anywhere on the screen."));
        return rows;
    }
}
