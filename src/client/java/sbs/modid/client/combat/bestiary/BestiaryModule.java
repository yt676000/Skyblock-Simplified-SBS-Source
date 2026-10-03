/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.bestiary;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.ui.hud.edit.ui.HudEditorScreen;
import sbs.modid.client.ui.settings.SettingRow;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;

import java.util.List;

/**
 * Bestiary Tracker module (Skills): pin a mob and see how many kills remain to max its Bestiary,
 * read from the in-game Bestiary menu. Self-registered via
 * {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 */
public final class BestiaryModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public BestiaryModule() {
    }

    @Override
    public String id() {
        return "bestiary_tracker";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.SKILLS;
    }

    @Override
    public String displayName() {
        return "Bestiary Tracker";
    }

    @Override
    public String description() {
        return "Pin a mob: shows the kills left to max its Bestiary (read from the Bestiary menu)";
    }

    @Override
    public int accentColor() {
        return 0xFFE0605F;
    }

    private static SBSConfig.BestiarySettings cfg() {
        return ConfigManager.getInstance().get().bestiary;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Bestiary Tracker", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("Shows a small card with how many kills you still need to max "
                                + "the Bestiary of one chosen mob. Open the Bestiary menu once so "
                                + "it can read your current count; after that it counts your kills "
                                + "live."),
                SettingRow.label("Open the Bestiary menu once to save the count, then it tracks kills live"),

                // The mob to keep on the HUD; matched loosely by name.
                SettingRow.text("Pin Mob", "mob name (e.g. Zombie)", 32,
                        () -> cfg().pinnedMob == null ? "" : cfg().pinnedMob,
                        v -> { cfg().pinnedMob = v == null ? "" : v.trim(); save(); })
                        .describe("The mob the card tracks. Type its name the way the Bestiary "
                                + "shows it, e.g. Zombie or Enderman - part of the name is enough."),

                SettingRow.button("Move / Resize Overlay", () -> open(new HudEditorScreen(
                        new HudElement[] {HudElement.BESTIARY_TRACKER}, "Edit Bestiary Tracker")))
                        .describe("Opens the editor where you drag the Bestiary card anywhere on "
                                + "the screen and scale it."),
                SettingRow.label("Shows while a mob is pinned"));
    }

    private static void open(net.minecraft.client.gui.screens.Screen screen) {
        net.minecraft.client.Minecraft.getInstance().setScreenAndShow(screen);
    }
}
