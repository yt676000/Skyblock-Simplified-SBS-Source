/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.ping;

import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.keybind.Keys;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.ui.settings.SettingRow;
import sbs.modid.client.ui.theme.ThemeColorPickerScreen;

import java.util.ArrayList;
import java.util.List;

/**
 * Ping Marker module (Quality of Life): a keybind that drops a temporary marker on whatever you are
 * looking at, up to a hundred and fifty blocks away.
 *
 * <p>The set and its lifetime live in {@link PingManager}, the ray in {@link PingRay}, the press in
 * {@link PingKeybinds}; this class registers the module and its rows. Self-registered via
 * {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 *
 * <p><b>There is no renderer in this package.</b> A ping is a transient waypoint, so the shared
 * {@code core/pathfinding/PathRenderer} draws it with the same box, beam, label and live distance
 * every other marker in the mod gets - and every ping is gone within seconds without ever reaching
 * {@code config.json}.
 *
 * <p><b>No area gate.</b> Unlike the marker sets this borrows its machinery from, a ping is not tied
 * to an island, a zone or an instance: it works everywhere, because pointing at something is not a
 * SkyBlock feature.
 *
 * <p>See {@code docs/features/ping-marker.md} - in particular its "Not done yet" list, which is
 * where the fact that none of this has been run in game is recorded.
 */
public final class PingModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public PingModule() {
    }

    @Override
    public String id() {
        return "ping_marker";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.QUALITY_OF_LIFE;
    }

    @Override
    public String displayName() {
        return "Ping Marker";
    }

    @Override
    public String description() {
        return "Drops a temporary marker on whatever you are looking at, and follows it if it moves";
    }

    @Override
    public int accentColor() {
        return 0xFFFFAA00;
    }

    private static SBSConfig.PingSettings cfg() {
        return ConfigManager.getInstance().get().ping;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    /** Saves, then pushes the change onto the pings already out so it is visible immediately. */
    private static void saveLive() {
        save();
        PingManager.getInstance().refresh();
    }

    @Override
    public List<SettingRow> settings() {
        List<SettingRow> rows = new ArrayList<>();

        rows.add(SettingRow.toggle("Ping Marker", () -> cfg().enabled,
                        () -> {
                            cfg().enabled = !cfg().enabled;
                            saveLive();
                        })
                .anchor("ping_enabled")
                .describe("Press the ping key and a marker appears on whatever your crosshair is "
                        + "on - a block, a chest, a mob - with a beam and a label you can find "
                        + "across a room and read through a wall. It disappears on its own after a "
                        + "few seconds and is never saved: pings are for calling something out, not "
                        + "for keeping. Aim at a ping you placed and press again to take it away."));

        rows.add(SettingRow.keybind("Ping Key", () -> cfg().key,
                        value -> {
                            cfg().key = value;
                            save();
                        })
                .anchor("ping_key")
                .describe("The key or mouse button that drops a ping. Default: "
                        + Keys.displayName(Keys.MOUSE_MIDDLE).getString() + ". "
                        + "§eThat is also Minecraft's own Pick Block button.§r Both still work - the "
                        + "mod adds to your input and never eats it, so a middle click both picks "
                        + "the block and drops the ping. Nothing was done to Minecraft's binding, "
                        + "so if the pair bothers you, change this one here and leave the vanilla "
                        + "controls alone. Never fires while a screen is open or you are typing."));

        rows.add(SettingRow.rangeSlider("Ping Range", 16, 512, () -> cfg().maxDistance,
                        value -> {
                            cfg().maxDistance = value;
                            save();
                        }, "m")
                .anchor("ping_range")
                .describe("How far the ping ray looks for something to land on. This is not the "
                        + "distance you can reach - nothing is touched, broken or clicked - so it "
                        + "goes far past arm's length on purpose. Pointing at nothing at all puts "
                        + "the marker at the far end of the ray, which is how you ping the horizon. "
                        + "Default: 150."));
        rows.add(SettingRow.label("§8Mobs can only be pinged as far as the server still sends them"));

        rows.add(SettingRow.rangeSlider("Ping Lifetime", 3, 120, () -> cfg().lifetimeSeconds,
                        value -> {
                            cfg().lifetimeSeconds = value;
                            save();
                        }, "s")
                .anchor("ping_lifetime")
                .describe("How long a ping stays before it fades out over its last second. Applies "
                        + "to pings placed from now on; the ones already out keep the lifetime they "
                        + "were given. Default: 12."));

        rows.add(SettingRow.rangeSlider("Maximum Pings", 1, 20, () -> cfg().maxPings,
                        value -> {
                            cfg().maxPings = value;
                            save();
                        }, "")
                .anchor("ping_max")
                .describe("How many pings may be up at once. Placing one past this removes the "
                        + "oldest, so the newest thing you pointed at is always on screen. "
                        + "Default: 5."));

        rows.add(SettingRow.color("Ping Colour", () -> cfg().colorHex,
                        () -> 0xFF000000 | SBSConfig.PingSettings.DEFAULT_PING_COLOR,
                        PingModule::openColorPicker)
                .anchor("ping_color")
                .describe("The colour of the ping's box, beam and label. Amber by default, which no "
                        + "other marker in the mod uses - a ping is meant to stand out from the "
                        + "waypoints already in the world."));

        rows.add(SettingRow.toggle("Distance On Labels", () -> cfg().showDistance,
                        () -> {
                            cfg().showDistance = !cfg().showDistance;
                            saveLive();
                        })
                .anchor("ping_distance")
                .describe("Adds how far away the ping is to its label (\"Ping 34m\"), counting down "
                        + "live as you walk to it. On by default."));

        rows.add(SettingRow.toggle("Stick To Mobs", () -> cfg().followEntities,
                        () -> {
                            cfg().followEntities = !cfg().followEntities;
                            save();
                        })
                .anchor("ping_follow")
                .describe("A ping that lands on a mob or a player travels with it instead of "
                        + "staying on the ground behind it. Off puts every ping on the block "
                        + "the ray hits, whatever is standing in front of it. On by default."));

        rows.add(SettingRow.toggle("Ping Sound", () -> cfg().sound,
                        () -> {
                            cfg().sound = !cfg().sound;
                            save();
                        })
                .anchor("ping_sound")
                .describe("A short blip when a ping is placed, so a press that lands off screen is "
                        + "still felt. Follows Minecraft's own volume. On by default."));

        rows.add(SettingRow.rangeSlider("Ping Sound Volume", 0, 100, () -> cfg().soundVolume,
                        value -> {
                            cfg().soundVolume = value;
                            save();
                        }, "%")
                .anchor("ping_sound_volume")
                .describe("How loud that blip is. 0 is silent, the same as switching the sound off. "
                        + "Default: 50."));

        rows.add(SettingRow.label("§8Now: " + PingManager.getInstance().status()));
        return rows;
    }

    private static void openColorPicker() {
        GuiStateManager state = GuiStateManager.getInstance();
        net.minecraft.client.Minecraft.getInstance().setScreenAndShow(new ThemeColorPickerScreen(
                "Ping Marker", cfg().colorHex,
                value -> {
                    cfg().colorHex = value;
                    saveLive();
                },
                state.getCurrentScreen()));
    }
}
