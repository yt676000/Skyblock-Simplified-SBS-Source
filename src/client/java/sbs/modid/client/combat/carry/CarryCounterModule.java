/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.carry;

import sbs.modid.client.combat.carry.logic.CarryCounter;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.ui.hud.edit.ui.HudEditorScreen;
import sbs.modid.client.ui.settings.SettingRow;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;

import java.util.List;

/**
 * Slayer Carry Counter module (Quality of Life): point the tracker at the person you are carrying
 * with {@code /sbs trackcarry <boss> <player>} and every slayer boss you help kill next to them is
 * counted for that person, with a running total on the HUD.
 *
 * <p>The counting logic and the {@code /sbs trackcarry} command live in {@link CarryCounter}; this
 * class only registers the module and its settings rows. Self-registered via
 * {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 */
public final class CarryCounterModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public CarryCounterModule() {
    }

    @Override
    public String id() {
        return "slayer_carry_counter";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.PARTY_CHAT;
    }

    @Override
    public String displayName() {
        return "Slayer Carry Counter";
    }

    @Override
    public String description() {
        return "Count slayer bosses you carry for someone - /sbs trackcarry <boss> <player>";
    }

    @Override
    public int accentColor() {
        return 0xFFE0A14D;
    }

    private static SBSConfig.CarryCounterSettings cfg() {
        return ConfigManager.getInstance().get().carryCounter;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Slayer Carry Counter", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("Counts the slayer bosses you carry someone through. Start with "
                                + "/sbs trackcarry <boss> <player> (e.g. /sbs trackcarry emant4 "
                                + "Steve) and every matching boss killed next to that player adds "
                                + "one to the counter."),
                SettingRow.label("/sbs trackcarry <boss> <player>  -  e.g. emant4, blaze3, rev5"),

                SettingRow.toggle("Auto-Detect Kills", () -> cfg().autoDetect,
                        () -> { cfg().autoDetect = !cfg().autoDetect; save(); })
                        .describe("Counts a carry automatically when the tracked boss dies close to "
                                + "you and the customer. Off, nothing is counted by itself - you "
                                + "add kills by hand with /sbs trackcarry +."),
                SettingRow.label("Counts a boss the moment it dies next to you; off = manual +/- only"),
                SettingRow.rangeSlider("Detect Radius", 5, 30, () -> cfg().detectRadius,
                        value -> { cfg().detectRadius = value; save(); }, "block")
                        .describe("How close (in blocks) a dying boss has to be to count "
                                + "automatically. Larger catches kills across the arena, smaller "
                                + "avoids counting other people's bosses in a crowded area."),

                SettingRow.toggle("Show Overlay", () -> cfg().showOverlay,
                        () -> { cfg().showOverlay = !cfg().showOverlay; save(); })
                        .describe("A small card on screen with one line per tracked carry: who, "
                                + "which boss, and the count so far."),
                SettingRow.button("Move / Resize Overlay", () -> open(new HudEditorScreen(
                        new HudElement[] {HudElement.CARRY_COUNTER}, "Edit Carry Counter")))
                        .describe("Opens the editor where you drag the carry card anywhere on the "
                                + "screen and scale it."),

                SettingRow.toggle("Announce in Chat", () -> cfg().announce,
                        () -> { cfg().announce = !cfg().announce; save(); })
                        .describe("Prints a line in your own chat each time a carry is counted, so "
                                + "you see the counter moving without looking at the card."),
                SettingRow.toggle("Party Chat Progress", () -> cfg().partyAnnounce,
                        () -> { cfg().partyAnnounce = !cfg().partyAnnounce; save(); })
                        .describe("Sends the progress to party chat after each kill (/pc carry "
                                + "<type> <count>/<goal> <player>), so the person being carried "
                                + "sees it too."),
                SettingRow.label("On each kill sends /pc carry <type> <count>/<goal> <player>"),

                SettingRow.label("— Boss Highlight —"),
                SettingRow.toggle("Highlight Carried Boss", () -> cfg().highlightEnabled,
                        () -> { cfg().highlightEnabled = !cfg().highlightEnabled; save(); })
                        .describe("Draws a box around slayer bosses through walls, so you find the "
                                + "boss you are supposed to kill without hunting for it. The "
                                + "toggles below narrow down whose bosses get boxed."),
                SettingRow.label("Boxes slayer bosses – owned by party / carry, or all (below)"),
                SettingRow.enumOptions("Highlight Color", () -> cfg().highlightColor,
                        value -> { cfg().highlightColor = value; save(); }, v -> v.displayName())
                        .anchor("highlight_color", "esp_color")
                        .describe("The color of the boss boxes. Click to cycle through the "
                                + "choices."),
                SettingRow.toggle("Highlight Name Label", () -> cfg().highlightLabel,
                        () -> { cfg().highlightLabel = !cfg().highlightLabel; save(); })
                        .anchor("highlight_name_label", "esp_name_label")
                        .describe("Writes the boss name above each box, useful when several boxes "
                                + "are on screen at once."),
                SettingRow.toggle("Owned Bosses Only", () -> cfg().highlightOwnedOnly,
                        () -> { cfg().highlightOwnedOnly = !cfg().highlightOwnedOnly; save(); })
                        .describe("Only boxes bosses belonging to your party members or carry "
                                + "customers, instead of every slayer boss in the lobby. Keeps a "
                                + "public slayer area readable."),
                SettingRow.label("Only party members' / carry targets' bosses (Slayed-by or ~8 blocks)"),
                SettingRow.toggle("Only Carried Boss Type", () -> cfg().highlightActiveBossOnly,
                        () -> { cfg().highlightActiveBossOnly = !cfg().highlightActiveBossOnly; save(); })
                        .describe("Only boxes the boss type you are currently carrying (from /sbs "
                                + "trackcarry), ignoring unrelated slayer bosses."),
                SettingRow.toggle("Show Pointer Lines", () -> cfg().highlightTracer,
                        () -> { cfg().highlightTracer = !cfg().highlightTracer; save(); })
                        .anchor("show_tracers")
                        .describe("Draws a thin line from your crosshair to each boxed boss, so "
                                + "you can follow it when the boss is behind you or far away."),
                SettingRow.label("A line from your crosshair to each boxed boss"),
                SettingRow.label("Hides other players' slayers in a public lobby"),

                SettingRow.label("— Minibosses —"),
                SettingRow.toggle("Show Minibosses While Carrying", () -> cfg().minibossHighlight,
                        () -> { cfg().minibossHighlight = !cfg().minibossHighlight; save(); })
                        .describe("Normally the Slayer module hides minibosses when you have no "
                                + "quest of your own. This shows them anyway while a carry is "
                                + "active, because on a carry the quest belongs to the customer. "
                                + "Colors and alerts come from the Slayer module's settings."),
                SettingRow.label("The Slayer module hides its miniboss alert + highlight without"),
                SettingRow.label("a quest of your own; on a carry the quest is the customer's."),
                SettingRow.label("§7Uses the Slayer module's miniboss colour and toggles."),

                SettingRow.label("+/- adjust, done finishes, list shows all, clear resets"));
    }

    private static void open(net.minecraft.client.gui.screens.Screen screen) {
        net.minecraft.client.Minecraft.getInstance().setScreenAndShow(screen);
    }
}
