/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.browser;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.ui.settings.SettingRow;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;

import java.util.List;

/**
 * Web Browser module (Quality of Life): a movable, resizable in-game browser you can drag anywhere
 * and keep watching over the game world - e.g. YouTube while farming. Reuses the MCEF/Chromium engine
 * already shipped for Item Price History.
 *
 * <p>Self-registered via {@code META-INF/services/sbs.modid.client.core.module.SbsModule}; state lives in
 * {@link SBSConfig.BrowserSettings}.
 */
public final class BrowserModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public BrowserModule() {
    }

    @Override
    public String id() {
        return "web_browser";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.QUALITY_OF_LIFE;
    }

    @Override
    public String displayName() {
        return "Web Browser";
    }

    @Override
    public String description() {
        return "A movable in-game browser (Google / YouTube) you can watch while playing";
    }

    @Override
    public int accentColor() {
        return 0xFF8FD14D;
    }

    private static SBSConfig.BrowserSettings cfg() {
        return ConfigManager.getInstance().get().browser;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Web Browser", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("A real browser window inside the game - watch a video or read a "
                                + "wiki while playing. Drag its top strip to move it, its "
                                + "bottom-right corner to resize."),
                SettingRow.label("A Chromium browser window you can drag anywhere on screen"),

                SettingRow.button("Open Browser", () -> WebBrowserManager.getInstance().toggleScreen())
                        .describe("Opens (or fronts) the browser window."),
                SettingRow.keybind("Open / Close Key", () -> cfg().openKey,
                        key -> { cfg().openKey = key; save(); })
                        .describe("A key that opens and closes the browser while playing. Click "
                                + "the row, press a key; Esc unbinds."),
                SettingRow.label("Press the key in-world to open it; Esc keeps it playing in the corner"),

                SettingRow.toggle("Keep Playing In World", () -> cfg().showInWorld,
                        () -> { cfg().showInWorld = !cfg().showInWorld; save(); })
                        .describe("When you press Esc, the page keeps playing in the corner of the "
                                + "screen instead of closing - a video keeps running while you "
                                + "farm. Off, Esc hides the browser entirely."),
                SettingRow.label("Keeps the page drawn over the game so a video plays while you farm"),

                SettingRow.button("Close Browser", () -> WebBrowserManager.getInstance().closeBrowser())
                        .describe("Fully closes the browser and frees its memory - stronger than "
                                + "Esc, which only hides it."),
                SettingRow.label("Drag the top strip to move, the bottom-right corner to resize"));
    }
}
