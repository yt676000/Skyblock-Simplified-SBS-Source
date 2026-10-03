/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.garden;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.ModuleSubgroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.skills.garden.logic.SprayTracker;
import sbs.modid.client.skills.garden.model.SprayMaterial;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.ArrayList;
import java.util.List;

/**
 * Sprayonator module (Skills): a Garden card showing the running spray as its item icon plus a
 * countdown, and which material was sprayed before it.
 *
 * <p>Reading and state live in {@link SprayTracker}, drawing in
 * {@link sbs.modid.client.skills.garden.render.SprayonatorHud}; this class only registers the module
 * and its rows. Self-registered via
 * {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 */
public final class SprayonatorModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public SprayonatorModule() {
    }

    @Override
    public String id() {
        return "sprayonator";
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
        return "Sprayonator";
    }

    @Override
    public String description() {
        return "Which spray is running, with its icon and countdown, and what you sprayed before";
    }

    @Override
    public int accentColor() {
        return 0xFF6FC2A0;
    }

    private static SBSConfig.SprayonatorSettings cfg() {
        return ConfigManager.getInstance().get().sprayonator;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        List<SettingRow> rows = new ArrayList<>();
        rows.add(SettingRow.toggle("Sprayonator", () -> cfg().enabled,
                () -> { cfg().enabled = !cfg().enabled; save(); })
                .describe("A Garden card with the spray you have running: its item icon, the plot "
                        + "and the time left. Needs the Pests widget turned on in Hypixel's "
                        + "/widgets, which is where the spray timer comes from."));
        rows.add(SettingRow.label("Only ever drawn while the Pests widget is on the tab list"));

        rows.add(SettingRow.toggle("Show Loaded Material", () -> cfg().showSelected,
                () -> { cfg().showSelected = !cfg().showSelected; save(); })
                .describe("While nothing is sprayed, show what the Sprayonator is currently set "
                        + "to - read off the item in your inventory, so you see what the next "
                        + "spray would be before you fire it."));
        rows.add(SettingRow.toggle("Show Last Spray", () -> cfg().showLast,
                () -> { cfg().showLast = !cfg().showLast; save(); })
                .describe("A second line with the material sprayed before the current one, and "
                        + "how long ago it ran out. Remembered across restarts."));
        rows.add(SettingRow.toggle("Show Plot", () -> cfg().showPlot,
                () -> { cfg().showPlot = !cfg().showPlot; save(); })
                .describe("Name the sprayed plot beside the material, when the widget states it."));
        rows.add(SettingRow.label(status()));

        rows.add(SettingRow.label("What each material pulls in:"));
        for (SprayMaterial material : SprayMaterial.all()) {
            rows.add(SettingRow.label(material.displayName() + " - " + material.attracts()));
        }
        return List.copyOf(rows);
    }

    /** The live spray state as a line for the settings page (a snapshot from page build time). */
    private static String status() {
        SprayTracker tracker = SprayTracker.getInstance();
        SprayMaterial active = tracker.active();
        if (active != null) {
            return "Spraying " + active.displayName() + " - "
                    + sbs.modid.client.skills.farming.model.FarmingText.duration(tracker.remainingMs())
                    + " left";
        }
        SprayMaterial selected = tracker.selected();
        return selected == null
                ? "No spray running"
                : "No spray running - Sprayonator loaded with " + selected.displayName();
    }
}
