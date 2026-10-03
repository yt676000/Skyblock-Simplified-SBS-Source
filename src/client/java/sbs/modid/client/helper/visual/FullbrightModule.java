/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.visual;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.helper.visual.logic.Fullbright;
import sbs.modid.client.ui.settings.SettingRow;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;

import java.util.List;

/**
 * Fullbright module (Visuals): light the world uniformly, from the settings row or a key.
 *
 * <p>Self-registered via {@code META-INF/services/sbs.modid.client.core.module.SbsModule}. The work is in
 * {@link Fullbright} and {@code LightmapFullbrightMixin}; both read the config live, so the toggle
 * applies within a tick and needs no restart.
 */
public final class FullbrightModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public FullbrightModule() {
    }

    @Override
    public String id() {
        return "fullbright";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.VISUALS;
    }

    @Override
    public String displayName() {
        return "Fullbright";
    }

    @Override
    public String description() {
        return "Light the world uniformly - caves and dark rooms without torches or a gamma slider";
    }

    @Override
    public int accentColor() {
        return 0xFFFFD64D;
    }

    private static SBSConfig.FullbrightSettings cfg() {
        return ConfigManager.getInstance().get().fullbright;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Fullbright", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("Lights the whole world evenly - caves, dungeons and night look "
                                + "like day. Your Minecraft brightness setting is not touched, so "
                                + "there is nothing to restore when you turn it off."),
                SettingRow.keybind("Toggle Key", () -> cfg().toggleKey,
                        key -> { cfg().toggleKey = key; save(); })
                        .describe("A key that flips Fullbright on and off while playing. Click the "
                                + "row, press a key; Esc unbinds."),
                SettingRow.label("Your gamma setting is never changed - nothing to restore"));
    }
}
