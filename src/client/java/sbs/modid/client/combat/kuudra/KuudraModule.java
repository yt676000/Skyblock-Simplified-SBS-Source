/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.kuudra;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.ui.HudEditorScreen;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.List;

/**
 * Kuudra module (Combat): the whole Crimson Isle boss run - phase tracking, the waypoints each phase
 * needs, the call-outs that have to be made in the first fifteen seconds, and the card that says how
 * long any of it took. Self-registered via
 * {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 *
 * <p><b>Everything that talks to other players is off by default.</b> The no-pre call, the fresh
 * announcement and the ability call-outs all send party chat, and a mod that starts typing in
 * somebody's party the first time they queue is a mod they turn off. Each is its own toggle, and each
 * is opt-in.
 */
public final class KuudraModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public KuudraModule() {
    }

    @Override
    public String id() {
        return "kuudra";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.COMBAT;
    }

    @Override
    public String displayName() {
        return "Kuudra";
    }

    @Override
    public String description() {
        return "Phase tracking, supply and build waypoints, no-pre call-outs and run splits";
    }

    @Override
    public int accentColor() {
        return 0xFFFF7A2E;
    }

    private static SBSConfig.KuudraSettings cfg() {
        return ConfigManager.getInstance().get().kuudra;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Kuudra", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("Everything for a Kuudra run: which phase it is in, waypoints on "
                                + "the crates, the piles and the pods, the fresh countdown, the "
                                + "no-pre call, and a card with the splits. Nothing below reads, "
                                + "draws or says anything while this is off."),
                SettingRow.label("§8Only ever active inside Kuudra's Hollow"),

                // ---------------------------------------------------------------- phase one
                SettingRow.label("§bSupplies"),
                SettingRow.toggle("Pile Waypoints", () -> cfg().pileWaypoints,
                        () -> { cfg().pileWaypoints = !cfg().pileWaypoints; save(); })
                        .describe("A beam on each of the six crate piles on the platform, named, so "
                                + "the one you are carrying to is findable from the lava."),
                SettingRow.toggle("Hide Finished Piles", () -> cfg().hideDonePiles,
                        () -> { cfg().hideDonePiles = !cfg().hideDonePiles; save(); })
                        .describe("Stop drawing a pile once its crate is in. On, the platform empties "
                                + "out as the phase goes on and what is left is what you still owe."),
                SettingRow.enumOptions("Pile Colour", () -> cfg().pileColor,
                        value -> { cfg().pileColor = value; save(); }, v -> v.displayName())
                        .describe("Colour of the pile beams. Click to cycle."),
                SettingRow.toggle("Supply Waypoints", () -> cfg().supplyWaypoints,
                        () -> { cfg().supplyWaypoints = !cfg().supplyWaypoints; save(); })
                        .describe("A beam on every crate that has surfaced, with how far away it is. "
                                + "The crates are carried around down in the lava, so the marker "
                                + "moves with them rather than sitting on a spawn point."),
                SettingRow.enumOptions("Supply Colour", () -> cfg().supplyColor,
                        value -> { cfg().supplyColor = value; save(); }, v -> v.displayName())
                        .describe("Colour of the crate beams. Click to cycle."),

                SettingRow.toggle("No-Pre Call-out", () -> cfg().noPreAlert,
                        () -> { cfg().noPreAlert = !cfg().noPreAlert; save(); })
                        .describe("Seven spots can produce a crate and only six ever do, so one "
                                + "player is always standing at an empty one. Watches your own spot "
                                + "and flashes the call the moment it is certain - it waits for at "
                                + "least one crate to have surfaced somewhere, so a slow server is "
                                + "not mistaken for an empty spot."),
                SettingRow.intField("Call After", 3, 30, () -> cfg().noPreDelaySeconds,
                        value -> { cfg().noPreDelaySeconds = value; save(); }, "s")
                        .describe("How long after the phase starts the call is allowed to fire. "
                                + "Lower calls sooner and risks calling a late crate; higher is "
                                + "safer and later."),
                SettingRow.toggle("Say No-Pre In Party", () -> cfg().noPreToParty,
                        () -> { cfg().noPreToParty = !cfg().noPreToParty; save(); })
                        .describe("Also send the call to party chat as \"No Triangle!\". Off by "
                                + "default - it types on your behalf. The words sent are always the "
                                + "mod's own; nothing anybody else typed is ever repeated."),
                SettingRow.toggle("Second Supply Call", () -> cfg().secondSupplyAlert,
                        () -> { cfg().secondSupplyAlert = !cfg().secondSupplyAlert; save(); })
                        .describe("Also call your second spot when it comes up empty, once your "
                                + "first one is dealt with."),
                SettingRow.toggle("No-Pre Sound", () -> cfg().noPreSound,
                        () -> { cfg().noPreSound = !cfg().noPreSound; save(); })
                        .describe("Ping with the no-pre call-out."),
                SettingRow.label("§8Someone else's \"no tri\" in party chat lights that pile up too"),

                SettingRow.toggle("Supply Alerts", () -> cfg().supplyAlert,
                        () -> { cfg().supplyAlert = !cfg().supplyAlert; save(); })
                        .describe("Flash when the sixth crate goes in."),
                SettingRow.toggle("Dropped-Crate Alert", () -> cfg().supplyDropAlert,
                        () -> { cfg().supplyDropAlert = !cfg().supplyDropAlert; save(); })
                        .describe("Flash when somebody drops a crate back into the lava - that crate "
                                + "has to be fished out again and nobody reads that line in time."),
                SettingRow.toggle("Supply Sound", () -> cfg().supplySound,
                        () -> { cfg().supplySound = !cfg().supplySound; save(); })
                        .describe("Ping with the supply alerts."),

                // ---------------------------------------------------------------- phase two
                SettingRow.label("§bBuild"),
                SettingRow.toggle("Build Waypoints", () -> cfg().buildWaypoints,
                        () -> { cfg().buildWaypoints = !cfg().buildWaypoints; save(); })
                        .describe("A beam on each ballista pile coloured by how far along it is, red "
                                + "through green, with its percentage. Answers \"which one is "
                                + "furthest behind\" from across the platform."),
                SettingRow.toggle("Fresh Countdown", () -> cfg().freshTimers,
                        () -> { cfg().freshTimers = !cfg().freshTimers; save(); })
                        .describe("The seconds left on Fresh Tools over each fresh player's head. "
                                + "Yours always shows; a teammate's needs their client to have "
                                + "announced it, because Hypixel only tells the player who took the "
                                + "tools."),
                SettingRow.toggle("Fresh Call-out", () -> cfg().freshAlert,
                        () -> { cfg().freshAlert = !cfg().freshAlert; save(); })
                        .describe("Flash FRESH when you pick the tools up."),
                SettingRow.toggle("Say Fresh In Party", () -> cfg().freshToParty,
                        () -> { cfg().freshToParty = !cfg().freshToParty; save(); })
                        .describe("Send \"FRESH! (43%)\" to party chat when you go fresh, so the "
                                + "others start a different pile. Off by default - it types on your "
                                + "behalf."),
                SettingRow.toggle("Fresh Sound", () -> cfg().freshSound,
                        () -> { cfg().freshSound = !cfg().freshSound; save(); })
                        .describe("Ping with the fresh call-out."),

                // ---------------------------------------------------------------- phase three/four
                SettingRow.label("§bStun & Boss"),
                SettingRow.toggle("Pod Waypoints", () -> cfg().stunWaypoints,
                        () -> { cfg().stunWaypoints = !cfg().stunWaypoints; save(); })
                        .describe("Boxes on the three pods inside Kuudra's mouth, so the one you are "
                                + "breaking is found without turning on the spot."),
                SettingRow.enumOptions("Pod Colour", () -> cfg().stunColor,
                        value -> { cfg().stunColor = value; save(); }, v -> v.displayName())
                        .describe("Colour of the pod boxes. Click to cycle."),
                SettingRow.toggle("Kuudra Hitbox", () -> cfg().kuudraHitbox,
                        () -> { cfg().kuudraHitbox = !cfg().kuudraHitbox; save(); })
                        .describe("Box Kuudra on his real hitbox. He is far larger than he looks and "
                                + "shots that feel like hits are not - the box is where the damage "
                                + "actually registers."),
                SettingRow.toggle("Line To Kuudra", () -> cfg().bossTracer,
                        () -> { cfg().bossTracer = !cfg().bossTracer; save(); })
                        .describe("Draw a line from your crosshair to him, through walls. He surfaces "
                                + "behind you as often as in front."),
                SettingRow.enumOptions("Kuudra Colour", () -> cfg().bossColor,
                        value -> { cfg().bossColor = value; save(); }, v -> v.displayName())
                        .describe("Colour of the hitbox and the line. Click to cycle."),
                SettingRow.toggle("Danger Zone Call-out", () -> cfg().dangerAlert,
                        () -> { cfg().dangerAlert = !cfg().dangerAlert; save(); })
                        .describe("Reads the coloured floor tile under your feet in the last phase "
                                + "and flashes DANGER as it winds up, JUMP on the beat. The floor is "
                                + "the only warning the slam gives and it is the one place nobody is "
                                + "looking."),
                SettingRow.toggle("Danger Sound", () -> cfg().dangerSound,
                        () -> { cfg().dangerSound = !cfg().dangerSound; save(); })
                        .describe("Ping with the danger call-out."),

                // ---------------------------------------------------------------- pearls
                SettingRow.label("§bPearls"),
                SettingRow.toggle("Pearl Waypoints", () -> cfg().pearlWaypoints,
                        () -> { cfg().pearlWaypoints = !cfg().pearlWaypoints; save(); })
                        .describe("Your own recorded pearl throws, drawn only while you are standing "
                                + "in the area they belong to. A marker turns green when you are "
                                + "actually looking at the pitch it was recorded at."),
                SettingRow.enumOptions("Pearl Colour", () -> cfg().pearlColor,
                        value -> { cfg().pearlColor = value; save(); }, v -> v.displayName())
                        .describe("Colour of pearl markers that have no colour of their own. Click "
                                + "to cycle."),
                SettingRow.label("§8Record with §f/sbs kuudra mark§8 - stand on your block, look at your pitch"),
                SettingRow.label("§8Stored in config/sbs/kuudrapearls.txt; §f/sbs kuudra§8 lists the rest"),

                // ---------------------------------------------------------------- team
                SettingRow.label("§bTeam"),
                SettingRow.toggle("Announce Abilities", () -> cfg().abilityAnnounce,
                        () -> { cfg().abilityAnnounce = !cfg().abilityAnnounce; save(); })
                        .describe("Send the spell you just cast and where you cast it to party chat, "
                                + "so the team can stand in it. Off by default - it types on your "
                                + "behalf."),
                SettingRow.toggle("Ichor Pool Markers", () -> cfg().ichorPoolMarkers,
                        () -> { cfg().ichorPoolMarkers = !cfg().ichorPoolMarkers; save(); })
                        .describe("Draw a ring on the floor where a pool was cast - yours always, a "
                                + "teammate's when their client announced the coordinates."),
                SettingRow.toggle("Announce Mana Drain", () -> cfg().manaDrainAnnounce,
                        () -> { cfg().manaDrainAnnounce = !cfg().manaDrainAnnounce; save(); })
                        .describe("Report how much mana a team buff cost and how many people it "
                                + "actually caught. Off by default - it types on your behalf."),

                // ---------------------------------------------------------------- card
                SettingRow.label("§bCard"),
                SettingRow.toggle("Kuudra HUD", () -> cfg().showHud,
                        () -> { cfg().showHud = !cfg().showHud; save(); })
                        .describe("The run card: tier and phase, the run and phase clocks, and "
                                + "whichever of the supply count, the build percentage or Kuudra's "
                                + "health the current phase is about."),
                SettingRow.toggle("Phase Call-out", () -> cfg().phaseAlert,
                        () -> { cfg().phaseAlert = !cfg().phaseAlert; save(); })
                        .describe("Flash the name of each phase as the run enters it."),
                SettingRow.toggle("Phase Sound", () -> cfg().phaseAlertSound,
                        () -> { cfg().phaseAlertSound = !cfg().phaseAlertSound; save(); })
                        .describe("Ping with the phase call-out."),
                SettingRow.toggle("Show Splits", () -> cfg().showSplits,
                        () -> { cfg().showSplits = !cfg().showSplits; save(); })
                        .describe("Keep each finished phase's time on the card. Hypixel reports only "
                                + "the total, so this is the only record of where a run went."),
                SettingRow.toggle("Show Supply Times", () -> cfg().showSupplyTimes,
                        () -> { cfg().showSupplyTimes = !cfg().showSupplyTimes; save(); })
                        .describe("List who brought in each crate and how long it took them."),
                SettingRow.button("Edit Kuudra GUI", () -> open(new HudEditorScreen(
                        new HudElement[] {HudElement.KUUDRA}, "Edit Kuudra GUI")))
                        .describe("Opens the editor where you drag the Kuudra card anywhere on the "
                                + "screen and scale it."),

                SettingRow.toggle("Detection Log", () -> cfg().debugLog,
                        () -> { cfg().debugLog = !cfg().debugLog; save(); })
                        .describe("Write every phase change, supply event and call-out to the log "
                                + "under [SBS][Kuudra]. Several of these are read off Hypixel "
                                + "messages that can be re-worded without notice - this is how a "
                                + "wrong one is found and fixed against a real run."));
    }

    private static void open(net.minecraft.client.gui.screens.Screen screen) {
        net.minecraft.client.Minecraft.getInstance().setScreenAndShow(screen);
    }
}
