/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.blood;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.ui.HudEditorScreen;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.List;

/**
 * Blood Helper module (Dungeons): the lines and boxes on the Watcher's mobs, the spawn markers, the
 * next-target pick, the start-killing call-out and the clear clock. Thorn's Spirit Bear is
 * deliberately NOT here - it belongs to the F4/M4 boss room and has its own module. Self-registered
 * via {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 */
public final class BloodHelperModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public BloodHelperModule() {
    }

    @Override
    public String id() {
        return "blood_helper";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.DUNGEONS;
    }

    @Override
    public String displayName() {
        return "Blood Helper";
    }

    @Override
    public String description() {
        return "Lines and boxes on the blood mobs, the start-killing call-out and the clear timer";
    }

    @Override
    public int accentColor() {
        return 0xFFD94F4F;
    }

    private static SBSConfig.BloodSettings cfg() {
        return ConfigManager.getInstance().get().blood;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Blood Helper", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("Helpers for the dungeon blood room: lines and boxes on the mobs, "
                                + "markers on the ones still spawning in, the next mob to swing at "
                                + "picked out, a call-out when they go live and a card with the "
                                + "clear timer."),
                SettingRow.label("Finds the room from the blood door line - or from the Watcher himself"),

                SettingRow.toggle("Highlight Blood Mobs", () -> cfg().highlightMobs,
                        () -> { cfg().highlightMobs = !cfg().highlightMobs; save(); })
                        .describe("Boxes the Watcher's mobs in the blood room: green for the one to "
                                + "hit next, red for the dangerous Master mobs, blue for the rest."),
                SettingRow.label("Green = hit this next, red = a \"Master\" mob, blue = the rest"),
                SettingRow.toggle("Mob Names", () -> cfg().showLabels,
                        () -> { cfg().showLabels = !cfg().showLabels; save(); })
                        .describe("Writes the mob's name above each box."),
                SettingRow.toggle("Pointer Lines", () -> cfg().showTracers,
                        () -> { cfg().showTracers = !cfg().showTracers; save(); })
                        .anchor("tracer_lines")
                        .describe("Draws a line from your crosshair to every blood mob - through "
                                + "walls, in the mob's own color, violet for the ones still "
                                + "spawning in and a thick green one to the mob you should hit "
                                + "next. You are fighting in circles in there: the line is what "
                                + "tells you which way to turn."),
                SettingRow.label("Lines to every mob, through walls - works with the boxes off"),
                SettingRow.toggle("Only What You Can See", () -> cfg().lineOfSightOnly,
                        () -> { cfg().lineOfSightOnly = !cfg().lineOfSightOnly; save(); })
                        .describe("Only boxes mobs you have a direct line of sight to. Off, mobs "
                                + "are boxed through walls too, which can turn the room into a "
                                + "wall of color. Boxes only - the lines always go through walls."),
                SettingRow.label("Off: mobs are boxed through walls too - the room can get loud"),

                SettingRow.toggle("Mark Spawning Mobs", () -> cfg().showSpawns,
                        () -> { cfg().showSpawns = !cfg().showSpawns; save(); })
                        .describe("Everything the room is about to do: the Watcher's spawn skulls "
                                + "boxed in orange with the wait left on them, the flight curve of "
                                + "the mobs he has already thrown with an amber hitbox and "
                                + "crosshair on the point each one stops rising - the moment it "
                                + "hangs still and can be shot out of the air - and a violet box on "
                                + "the ones that have landed but cannot be hit yet."),
                SettingRow.label("Orange skull = a mob spawns here, amber box = shoot it there, violet = not hittable yet"),

                SettingRow.toggle("Start-Killing Call-out", () -> cfg().startKillingAlert,
                        () -> { cfg().startKillingAlert = !cfg().startKillingAlert; save(); })
                        .describe("Flashes START KILLING above your crosshair and pings once the "
                                + "wait after the blood door is over - the same call-out style the "
                                + "other dungeon helpers use."),
                SettingRow.intField("Start Killing After", 1, 120, () -> cfg().startKillingSeconds,
                        value -> { cfg().startKillingSeconds = value; save(); }, "s")
                        .describe("Seconds from the blood door opening until the mobs are worth "
                                + "swinging at. The card counts this down and the call-out fires "
                                + "when it reaches zero."),
                SettingRow.label("§8Counted from \"The BLOOD DOOR has been opened\"; no countdown when that line is missed"),

                SettingRow.toggle("Blood Camp Move", () -> cfg().campMove,
                        () -> { cfg().campMove = !cfg().campMove; save(); })
                        .describe("For camping the room: the Watcher checks it on a three-second "
                                + "schedule, and a room that is already empty when he checks makes "
                                + "him cut his dialogue short. Times his opening line against his "
                                + "\"Let's see how you can handle this\", works out which check you "
                                + "are heading for and flashes KILL BLOOD just before it. Counted on "
                                + "the server's tick rate, so a laggy room does not shift the call."),
                SettingRow.label("§8Needs his opening line - it says so instead of guessing when that was missed"),
                SettingRow.toggle("Move Call To Chat", () -> cfg().campMoveChat,
                        () -> { cfg().campMoveChat = !cfg().campMoveChat; save(); })
                        .describe("Prints the predicted mark into your own chat the moment it is "
                                + "known, so you can see the call coming instead of only being "
                                + "flashed at. Nothing is sent to the party."),

                SettingRow.toggle("Blood HUD", () -> cfg().showHud,
                        () -> { cfg().showHud = !cfg().showHud; save(); })
                        .describe("The blood-room card: the countdown until the mobs are up, how "
                                + "long the clear has taken so far and how many mobs are left."),
                SettingRow.toggle("Clear Time To Chat", () -> cfg().chatOnClear,
                        () -> { cfg().chatOnClear = !cfg().chatOnClear; save(); })
                        .describe("Prints the final blood-clear time into your own chat when the "
                                + "room is done. Nothing is sent to other players."),
                SettingRow.label("§8Printed to your own chat only - nothing is sent to the party"),

                SettingRow.button("Edit Blood GUI", () -> open(new HudEditorScreen(
                        new HudElement[] {HudElement.BLOOD_HELPER}, "Edit Blood GUI")))
                        .describe("Opens the editor where you drag the blood card anywhere on the "
                                + "screen and scale it."));
    }

    private static void open(net.minecraft.client.gui.screens.Screen screen) {
        net.minecraft.client.Minecraft.getInstance().setScreenAndShow(screen);
    }
}
