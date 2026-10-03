/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.level;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.ui.settings.SettingRow;
import sbs.modid.client.ui.theme.ThemeColorPickerScreen;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * Level Colours module (Interface): your own colour table for the SkyBlock level.
 *
 * <p>The rows for the bands are <b>generated from the table</b> rather than written out, which is
 * what makes an arbitrary number of them possible on a settings page built from a flat row list -
 * the same thing the Fonts module does per font slot.
 *
 * <p><b>Row ids are anchored to the threshold, not to the position.</b> Anchoring to an index would
 * renumber every row below whenever a band is added or removed, and a stored reference to a row -
 * a favourite, a search jump - would then point at a different band. Keying on the level a band
 * starts at survives insertion and deletion, which is the property that matters.
 */
public final class LevelColorsModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public LevelColorsModule() {
    }

    @Override
    public String id() {
        return "level_colors";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.INTERFACE;
    }

    @Override
    public String displayName() {
        return "Level Colours";
    }

    @Override
    public String description() {
        return "Your own colours for the SkyBlock level, in bands you define";
    }

    @Override
    public int accentColor() {
        return 0xFFD9A441;
    }

    private static SBSConfig.LevelColorSettings cfg() {
        return ConfigManager.getInstance().get().levelColors;
    }

    private static List<LevelTier> tiers() {
        SBSConfig.LevelColorSettings settings = cfg();
        if (settings.tiers == null) {
            settings.tiers = LevelColors.defaultTiers();
        }
        return settings.tiers;
    }

    /** Saves, and tells the resolver its cached table is out of date. */
    private static void save() {
        ConfigManager.getInstance().save();
        LevelColors.invalidate();
    }

    @Override
    public List<SettingRow> settings() {
        List<SettingRow> rows = new ArrayList<>();
        rows.add(SettingRow.toggle("Colour The Level Myself", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                .describe("Off, the SkyBlock level is drawn exactly as Hypixel wrote it - which is "
                        + "what this mod has always done, and what stays right when Hypixel adds a "
                        + "colour tier nobody has seen yet. On, the bands below decide instead."));
        rows.add(SettingRow.label("§8Off = Hypixel's own colours. Nothing changes until you switch it on"));
        rows.add(SettingRow.label("§8Each band runs from its level until the next one starts;"));
        rows.add(SettingRow.label("§8the last one runs forever, so a rising level cap needs no change"));

        List<LevelTier> tiers = tiers();
        for (int i = 0; i < tiers.size(); i++) {
            LevelTier tier = tiers.get(i);
            int index = i;
            String anchor = "tier_" + tier.from;
            String name = tier.from == 0 ? "From level 0 (everything below the next band)"
                    : "From level " + tier.from;
            rows.add(SettingRow.intField(name, 0, LevelColors.MAX_LEVEL,
                            () -> current(index) == null ? 0 : current(index).from,
                            value -> {
                                LevelTier live = current(index);
                                if (live != null) {
                                    live.from = value;
                                    save();
                                }
                            }, "")
                    .anchor(anchor + "_from")
                    .describe("The level this band starts at. It runs until the next band begins."));
            rows.add(SettingRow.color("   Colour", () -> swatch(index), () -> SBSTheme.ACCENT,
                            () -> openPicker(index))
                    .anchor(anchor + "_color")
                    .describe("The colour for this band. Clear it in the picker to hand this band "
                            + "back to Hypixel's own colour."));
            rows.add(SettingRow.button("   Remove This Band", () -> {
                        LevelTier live = current(index);
                        if (live != null) {
                            tiers().remove(live);
                            save();
                        }
                    }).anchor(anchor + "_remove")
                    .describe("Drops this band. The one below it then runs up to the next one."));
        }

        rows.add(SettingRow.button("Add A Band", () -> {
                    List<LevelTier> live = tiers();
                    int next = live.isEmpty() ? 0 : live.get(live.size() - 1).from + 40;
                    live.add(new LevelTier(Math.min(next, LevelColors.MAX_LEVEL), "FFFFFF"));
                    save();
                }).describe("Adds a band above the highest one. Set its level and colour afterwards."));
        rows.add(SettingRow.button("Reset To The Shipped Bands", () -> {
                    cfg().tiers = LevelColors.defaultTiers();
                    save();
                }).describe("Puts the table back to what the mod ships with. Your master toggle is "
                        + "left as it is."));
        return List.copyOf(rows);
    }

    /**
     * The band at {@code index} in the live list, or {@code null} when the list has since changed
     * under a row that was built before it - a row's action can only ever be run after its list was
     * built, and the player may have removed a band in between.
     */
    private static LevelTier current(int index) {
        List<LevelTier> live = tiers();
        return index >= 0 && index < live.size() ? live.get(index) : null;
    }

    /** The hex the swatch shows, empty for a passthrough band so it reads as "not mine". */
    private static String swatch(int index) {
        LevelTier tier = current(index);
        return tier == null || tier.isPassthrough() ? "" : tier.hex;
    }

    private static void openPicker(int index) {
        LevelTier tier = current(index);
        if (tier == null) {
            return;
        }
        String start = tier.isPassthrough() ? LevelColors.toHex(SBSTheme.ACCENT) : tier.hex;
        Screen previous = GuiStateManager.getInstance().getCurrentScreen();
        Minecraft.getInstance().setScreenAndShow(new ThemeColorPickerScreen(
                "Level  •  from " + tier.from, start,
                value -> {
                    LevelTier live = current(index);
                    if (live != null) {
                        live.hex = value == null ? LevelTier.PASSTHROUGH : value;
                        save();
                    }
                }, previous));
    }
}
