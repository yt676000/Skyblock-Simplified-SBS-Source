/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.bestiary;

import sbs.modid.client.combat.mobhighlight.model.HighlightColor;
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
        return "Pin mobs with a ★ in the Bestiary menu: their progress stays on the HUD, optionally highlighted";
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
                        .describe("Shows a card with the Bestiary progress of the mobs you pinned "
                                + "with the ★ in the Bestiary menu. Opening the menu reads your "
                                + "counts; the tab list's Bestiary widget, or your kills nearby, keep "
                                + "them live."),
                SettingRow.label("Pin mobs with the ★ on their item in the Bestiary menu"),

                SettingRow.button("Pinned Mobs", () -> open(new BestiaryPinsScreen(
                        sbs.modid.client.core.api.GuiStateManager.getInstance().getCurrentScreen())))
                        .describe("The mobs on the card, in order: drag to reorder, switch "
                                + "\"Highlight in world\" per mob, or unpin."),
                SettingRow.toggle("Highlight New Pins", () -> cfg().highlightDefault,
                        () -> { cfg().highlightDefault = !cfg().highlightDefault; save(); })
                        .describe("Whether a mob you pin starts out boxed in the world (when in "
                                + "sight, like Mob Highlight). Change it per mob in Pinned Mobs."),
                SettingRow.options("Highlight Colour", BestiaryModule::colorNames,
                        () -> cfg().highlightColor.displayName(),
                        v -> { cfg().highlightColor = colorNamed(v); save(); })
                        .describe("The box colour for pinned mobs, so they stand apart from "
                                + "Mob Highlight's own picks."),
                SettingRow.rangeSlider("Rows on the Card", 1, 10, () -> cfg().maxRows,
                        v -> { cfg().maxRows = v; save(); }, "")
                        .describe("How many pinned mobs the card lists; the rest show as \"+N more\"."),

                SettingRow.button("Move / Resize Overlay", () -> open(new HudEditorScreen(
                        new HudElement[] {HudElement.BESTIARY_TRACKER}, "Edit Bestiary Tracker")))
                        .describe("Opens the editor where you drag the Bestiary card anywhere on "
                                + "the screen and scale it."),
                SettingRow.label("Shows while a mob is pinned"));
    }

    private static List<String> colorNames() {
        List<String> names = new java.util.ArrayList<>();
        for (HighlightColor color : HighlightColor.values()) {
            names.add(color.displayName());
        }
        return names;
    }

    private static HighlightColor colorNamed(String name) {
        for (HighlightColor color : HighlightColor.values()) {
            if (color.displayName().equals(name)) {
                return color;
            }
        }
        return HighlightColor.YELLOW;
    }

    private static void open(net.minecraft.client.gui.screens.Screen screen) {
        net.minecraft.client.Minecraft.getInstance().setScreenAndShow(screen);
    }
}
