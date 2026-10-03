/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.overlayinspector;

import net.minecraft.client.Minecraft;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.List;

/**
 * Overlay Inspector module (Interface): the settings page for the pointer that names the mod behind
 * each HUD element.
 *
 * <p>Self-registered via {@code META-INF/services/sbs.modid.client.core.module.SbsModule}. The
 * behaviour lives in {@link OverlayInspector}; this is identity plus rows.
 */
public final class OverlayInspectorModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public OverlayInspectorModule() {
    }

    @Override
    public String id() {
        return "overlay_inspector";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.INTERFACE;
    }

    @Override
    public String displayName() {
        return "Overlay Inspector";
    }

    @Override
    public String description() {
        return "A free mouse pointer over the game: hover any HUD overlay to see which mod draws it, click to open that mod's settings";
    }

    @Override
    public int accentColor() {
        return 0xFF3FB4FF;
    }

    private static SBSConfig.OverlayInspectorSettings cfg() {
        return ConfigManager.getInstance().get().overlayInspector;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Overlay Inspector", () -> cfg().enabled,
                        () -> {
                            cfg().enabled = !cfg().enabled;
                            if (!cfg().enabled) {
                                OverlayInspector.getInstance().deactivate();
                            }
                            save();
                        })
                        .describe("Lets you switch on a mouse pointer that moves over the running "
                                + "game without turning your view. Whatever the pointer is over is "
                                + "named by the mod that drew it - the fast way to find out which "
                                + "of your mods owns an overlay you want to change or switch off."),

                SettingRow.button("Start Inspecting", () -> {
                    Minecraft.getInstance().setScreenAndShow(null);
                    OverlayInspector.getInstance().activate();
                })
                        .describe("Closes this menu and switches the pointer on right away. Your "
                                + "camera stays frozen while it is up; press ESC (or the key below) "
                                + "to leave."),
                SettingRow.keybind("Inspect Overlays", () -> cfg().toggleKey,
                        key -> {
                            cfg().toggleKey = key;
                            save();
                        })
                        .describe("A key that switches the pointer on and off in-game, without "
                                + "coming back to this menu."),
                SettingRow.label("Left-click a highlighted overlay to open its mod's settings"),
                SettingRow.label("Right-click copies the mod's name, ESC leaves"),

                SettingRow.toggle("Show Mod List", () -> cfg().showModList,
                        () -> {
                            cfg().showModList = !cfg().showModList;
                            save();
                        })
                        .describe("A panel in the top-left listing every mod that drew anything on "
                                + "the current frame, busiest first. Useful for spotting an overlay "
                                + "too small or too faint to point at."),
                SettingRow.rangeSlider("Pointer Speed", 25, 300,
                        () -> cfg().pointerSpeed,
                        value -> {
                            cfg().pointerSpeed = value;
                            save();
                        }, "%")
                        .describe("How far the pointer travels per mouse movement. 100% matches "
                                + "your desktop cursor; lower it to aim at small elements."),
                SettingRow.toggle("Show Source Code Line", () -> cfg().showSource,
                        () -> {
                            cfg().showSource = !cfg().showSource;
                            save();
                        })
                        .describe("Adds the class and method the answer was read from to the card "
                                + "(\"via TimerHud.render\"). The mod name is worked out from the "
                                + "code that was drawing, so this is how you check an answer that "
                                + "looks wrong - and it names the exact overlay inside a mod that "
                                + "draws several."),
                SettingRow.toggle("Log Every Element", () -> cfg().logHovers,
                        () -> {
                            cfg().logHovers = !cfg().logHovers;
                            save();
                        })
                        .describe("Writes each newly pointed-at element to the log with the same "
                                + "detail. For reporting a wrong answer without having to "
                                + "reproduce it live."),
                SettingRow.toggle("Announce in Chat", () -> cfg().announce,
                        () -> {
                            cfg().announce = !cfg().announce;
                            save();
                        })
                        .describe("Prints a line in chat when the pointer switches on, with the "
                                + "reminder of what click and ESC do."),

                SettingRow.label("§8Elements drawn from inside another mod's code may report that mod"));
    }
}
