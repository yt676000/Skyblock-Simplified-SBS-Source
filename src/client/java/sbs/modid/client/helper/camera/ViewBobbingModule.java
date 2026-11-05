/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.camera;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.List;

/**
 * Disable View Bobbing module (Visuals): no camera or hand sway while walking.
 *
 * <p>Self-registered via {@code META-INF/services/sbs.modid.client.core.module.SbsModule}. The work
 * is in {@code ViewBobbingMixin}, which cancels {@code GameRenderer.bobView} and reads the config
 * live, so the toggle applies on the next frame.
 *
 * <p><b>On by default - a deliberate exception</b> to {@code core/AGENTS.md} §9 ("features that
 * change what the player sees ... are opt-in"), decided by the maintainer on 2026-10-06: it only
 * removes a cosmetic camera sway, hides nothing, and the vanilla "View Bobbing" option is never
 * touched (§9's first rule), so switching this off hands control straight back to it.
 */
public final class ViewBobbingModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public ViewBobbingModule() {
    }

    @Override
    public String id() {
        return "view_bobbing";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.VISUALS;
    }

    @Override
    public String displayName() {
        return "Disable View Bobbing";
    }

    @Override
    public String description() {
        return "No camera or hand sway while walking - your vanilla option stays as it is";
    }

    @Override
    public int accentColor() {
        return 0xFF7FB2E5;
    }

    private static SBSConfig.ViewBobbingSettings cfg() {
        return ConfigManager.getInstance().get().viewBobbing;
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Disable View Bobbing", () -> cfg().disable,
                        () -> { cfg().disable = !cfg().disable; ConfigManager.getInstance().save(); })
                        .describe("Removes the camera and hand sway while walking. The vanilla "
                                + "\"View Bobbing\" option is left as it is - turn this off and "
                                + "the vanilla option decides again. The tilt when you take "
                                + "damage is not affected."));
    }
}
