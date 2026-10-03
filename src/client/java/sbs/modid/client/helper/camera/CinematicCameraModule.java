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
import sbs.modid.client.helper.build.logic.Freecam;
import sbs.modid.client.helper.build.model.FreecamRules;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.List;

/**
 * Cinematic Camera: freecam as a pure camera for videos and montages - no selection, no outline, no
 * preview, clicks do nothing, and optionally a clean screen. Its own switch, key and
 * {@code /sbs freecam}, so it works with Build Tools off; the camera itself is the shared
 * {@link Freecam} in cinematic mode.
 *
 * <p>Self-registered via {@code META-INF/services}; every option writes to
 * {@link SBSConfig.CinematicCameraSettings} and applies at once.
 */
public final class CinematicCameraModule implements SbsModule {

    static {
        // The camera's input, HUD and packet hooks are wired when the class loads.
        Freecam.active();
    }

    /** ServiceLoader needs a public no-arg constructor. */
    public CinematicCameraModule() {
    }

    @Override
    public String id() {
        return "cinematic_camera";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.QUALITY_OF_LIFE;
    }

    @Override
    public String displayName() {
        return "Cinematic Camera";
    }

    @Override
    public String description() {
        return "A smooth free-flying camera with a clean screen, for videos - it never acts or selects";
    }

    @Override
    public int accentColor() {
        return 0xFF7FB8E0;
    }

    private static SBSConfig.CinematicCameraSettings cfg() {
        return ConfigManager.getInstance().get().cinematicCamera;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    /** The key, from {@code KeybindDispatch}. Inert while the module is off (start says why). */
    public static void onKeyPressed(int keyCode) {
        if (keyCode != 0 && keyCode == cfg().key && cfg().enabled) {
            Freecam.toggle(FreecamRules.Mode.CINEMATIC);
        }
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Cinematic Camera", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("A free camera for filming: you stay where you are while the view flies. "
                                + "Clicks do nothing, nothing is selected, and nothing extra is sent to the "
                                + "server. Works with Build Tools off. Off by default."),
                SettingRow.keybind("Cinematic Freecam Key", () -> cfg().key,
                        key -> { cfg().key = key; save(); })
                        .describe("Toggles cinematic freecam (also /sbs freecam). Pressing it in build "
                                + "freecam switches over without snapping back. Unbound by default."),

                SettingRow.label("Clean screen"),
                SettingRow.toggle("Hide HUD In Cinematic Freecam", () -> cfg().hideHud,
                        () -> { cfg().hideHud = !cfg().hideHud; save(); })
                        .describe("Hides the whole HUD - vanilla and every SBS card - like F1, while you "
                                + "fly. The Freecam chip shows for 2 s at the start only. On by default."),
                SettingRow.toggle("Show World Markers", () -> cfg().showWorldMarkers,
                        () -> { cfg().showWorldMarkers = !cfg().showWorldMarkers; save(); })
                        .describe("With the HUD hidden, still draw SBS markers in the world - holograms, "
                                + "waypoints, highlights. Off by default, for a clean shot."),

                SettingRow.label("Smooth camera"),
                SettingRow.rangeSlider("Movement Smoothing", 0, 95, () -> cfg().movementSmoothing,
                        value -> { cfg().movementSmoothing = value; save(); }, "%")
                        .describe("How gently the camera speeds up and slows down. 0 moves at once; "
                                + "higher glides longer. Default 70%."),
                SettingRow.toggle("Mouse Smoothing", () -> cfg().mouseSmoothing,
                        () -> { cfg().mouseSmoothing = !cfg().mouseSmoothing; save(); })
                        .describe("Mouse look through vanilla's cinematic camera smoothing while flying; "
                                + "your own setting comes back after. On by default."),
                SettingRow.toggle("Fine Speed Steps", () -> cfg().fineSpeeds,
                        () -> { cfg().fineSpeeds = !cfg().fineSpeeds; save(); })
                        .describe("The wheel steps through slower speeds (down to x0.02) for slow pans. "
                                + "On by default."),
                SettingRow.toggle("Noclip", () -> cfg().noclip,
                        () -> { cfg().noclip = !cfg().noclip; save(); })
                        .describe("The camera passes through blocks. On by default."),

                SettingRow.label("On servers"),
                SettingRow.toggle("Freecam On Servers", () -> cfg().multiplayer,
                        () -> {
                            cfg().multiplayer = !cfg().multiplayer;
                            if (cfg().multiplayer && !cfg().warned) {
                                cfg().warned = true;
                                sbs.modid.client.helper.build.logic.BuildChat.warn("Freecam on servers is at your own "
                                        + "risk: seeing into closed areas can count as an unfair advantage under "
                                        + "the server's rules.");
                            }
                            save();
                        })
                        .describe("Allows cinematic freecam on servers - only on your own Private Island "
                                + "and on Gardens (yours, or one you are visiting); not on someone else's "
                                + "island, nowhere else. Leaving those places turns it off at once. "
                                + "Off by default."),
                SettingRow.intField("Range On Servers", 4, 128, () -> cfg().range,
                        value -> { cfg().range = value; save(); }, "")
                        .describe("On servers the camera stays within this many blocks of you. Default 32."),
                SettingRow.intField("Range Singleplayer", 0, 512, () -> cfg().rangeSingleplayer,
                        value -> { cfg().rangeSingleplayer = value; save(); }, "")
                        .describe("An optional range limit in singleplayer; 0 = none. Default 0."),
                SettingRow.toggle("Hide Entities On Servers", () -> cfg().hideEntities,
                        () -> { cfg().hideEntities = !cfg().hideEntities; save(); })
                        .describe("On servers, other players, mobs, armor stands and drops are not drawn "
                                + "from the camera - so it cannot be used to scout. Singleplayer shows "
                                + "everything. On by default."));
    }
}
