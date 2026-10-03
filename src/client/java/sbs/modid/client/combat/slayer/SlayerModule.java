/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.slayer;

import sbs.modid.client.combat.slayer.logic.SlayerTracker;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.ui.hud.edit.ui.HudEditorScreen;
import sbs.modid.client.ui.settings.SettingRow;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;

import java.util.List;

/**
 * Slayer module (Skills): the session tracker (bosses, kill times, est. bosses/h, drop list,
 * profit + profit/h) plus the fight helpers – Voidgloom beacon and nukekubi highlights/alerts and
 * phase colours, miniboss alerts for every slayer, the Blaze fire-pillar warning and the Vampire
 * Twinclaws / Ichor helpers. Self-registered via
 * {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 */
public final class SlayerModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public SlayerModule() {
    }

    @Override
    public String id() {
        return "slayer";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.COMBAT;
    }

    @Override
    public String displayName() {
        return "Slayer";
    }

    @Override
    public String description() {
        return "Boss/profit tracker, fight helpers for every slayer, miniboss alerts";
    }

    @Override
    public int accentColor() {
        return 0xFFB44DFF;
    }

    private static SBSConfig.SlayerSettings cfg() {
        return ConfigManager.getInstance().get().slayer;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Slayer", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("Master switch for every slayer helper on this page. Off, none of "
                                + "them runs - no overlay, no highlights, no alerts."),

                SettingRow.toggle("Session Overlay", () -> cfg().showOverlay,
                        () -> { cfg().showOverlay = !cfg().showOverlay; save(); })
                        .describe("A small card on screen with your slayer session: bosses killed, "
                                + "average kill time, bosses per hour, drops and profit. It appears "
                                + "when you do slayers and hides itself after 10 minutes without one."),
                SettingRow.toggle("RNG Meter On The Card", () -> cfg().rngMeter,
                        () -> { cfg().rngMeter = !cfg().rngMeter; save(); })
                        .describe("Adds your RNG meter to the session card: how full it is, the "
                                + "drop you selected, what each boss added and how many bosses are "
                                + "left (an estimate - it depends on the tier you kill). Open the "
                                + "Slayer menu once so SBS learns your meter's goal; after that every "
                                + "boss updates it from chat."),
                SettingRow.toggle("RNG Drop Value", () -> cfg().rngMeterDropValue,
                        () -> { cfg().rngMeterDropValue = !cfg().rngMeterDropValue; save(); })
                        .describe("Shows what your selected RNG drop sells for right now, under the "
                                + "meter on the session card."),
                SettingRow.button("Move / Resize Overlay", () -> open(new HudEditorScreen(
                        new HudElement[] {HudElement.SLAYER_TRACKER}, "Edit Slayer Tracker")))
                        .describe("Opens the editor where you drag the session card anywhere on the "
                                + "screen and scale it."),
                SettingRow.button("Reset Session Stats",
                        () -> SlayerTracker.getInstance().resetSession())
                        .describe("Sets the session numbers (bosses, kill times, drops, profit) back "
                                + "to zero for a fresh grinding session."),
                SettingRow.toggle("Alert Sound", () -> cfg().alertSound,
                        () -> { cfg().alertSound = !cfg().alertSound; save(); })
                        .describe("Plays a short ping along with every slayer alert (beacon, "
                                + "miniboss, pillar...). Off, the alerts are silent text only."),

                SettingRow.label("— Ender Slayer (Voidgloom) —"),
                SettingRow.toggle("Highlight Beacon", () -> cfg().beaconHighlight,
                        () -> { cfg().beaconHighlight = !cfg().beaconHighlight; save(); })
                        .describe("Draws a colored box around the beacon the Voidgloom Seraph "
                                + "throws down. Break the beacon fast or the boss heals - the box "
                                + "makes it findable the moment it lands."),
                SettingRow.enumOptions("Beacon Color", () -> cfg().beaconColor,
                        value -> { cfg().beaconColor = value; save(); }, v -> v.displayName())
                        .describe("The color of that beacon box. Click to cycle through the choices."),
                SettingRow.toggle("Beacon Alert", () -> cfg().beaconAlert,
                        () -> { cfg().beaconAlert = !cfg().beaconAlert; save(); })
                        .describe("Flashes BEACON! above your crosshair the moment the boss places "
                                + "one, so you notice even while looking elsewhere."),
                SettingRow.toggle("Beacon Pointer Line", () -> cfg().beaconTracer,
                        () -> { cfg().beaconTracer = !cfg().beaconTracer; save(); })
                        .anchor("beacon_tracer")
                        .describe("Draws a line from your crosshair to the beacon, so you can run "
                                + "straight to it even when it lands behind you. Works on its own - "
                                + "you do not need the Show Pointer Lines switch further down."),
                SettingRow.toggle("Highlight Nukekubi Heads", () -> cfg().nukekubiHighlight,
                        () -> { cfg().nukekubiHighlight = !cfg().nukekubiHighlight; save(); })
                        .describe("Draws a box around the skulls (nukekubi fixations) the Voidgloom "
                                + "spawns below a third of its health. They settle around you and "
                                + "every one still alive doubles the damage you take, so finding "
                                + "them fast is what keeps the fight survivable. Deployables you or "
                                + "anyone else drops are left out of it."),
                SettingRow.enumOptions("Nukekubi Color", () -> cfg().nukekubiColor,
                        value -> { cfg().nukekubiColor = value; save(); }, v -> v.displayName())
                        .describe("The color of the skull boxes and their alert. Click to cycle."),
                SettingRow.toggle("Nukekubi Alert", () -> cfg().nukekubiAlert,
                        () -> { cfg().nukekubiAlert = !cfg().nukekubiAlert; save(); })
                        .describe("Flashes NUKEKUBI above your crosshair when a new flying skull "
                                + "appears near your boss."),
                SettingRow.toggle("Nukekubi Pointer Line", () -> cfg().nukekubiTracer,
                        () -> { cfg().nukekubiTracer = !cfg().nukekubiTracer; save(); })
                        .anchor("nukekubi_tracer")
                        .describe("Draws a line from your crosshair to every skull. They scatter "
                                + "around you and each one alive doubles the boss's damage, so the "
                                + "lines are what point you at the ones behind your back. Works on "
                                + "its own - you do not need the Show Pointer Lines switch further "
                                + "down."),
                SettingRow.toggle("Boss Phase Colors", () -> cfg().phaseHighlight,
                        () -> { cfg().phaseHighlight = !cfg().phaseHighlight; save(); })
                        .describe("Tints your boss box by what the boss is doing right now. For the "
                                + "Voidgloom: normal, beacon placed, or the 'hits' phase. For the "
                                + "other slayers the box switches to the Boss State Color below "
                                + "while the boss is enraged, protected or cocooned. One glance at "
                                + "the color tells you what to do."),
                SettingRow.enumOptions("Phase: Normal", () -> cfg().phaseNormalColor,
                        value -> { cfg().phaseNormalColor = value; save(); }, v -> v.displayName())
                        .describe("Boss color while nothing special is happening. Click to cycle."),
                SettingRow.enumOptions("Phase: Beacon", () -> cfg().phaseBeaconColor,
                        value -> { cfg().phaseBeaconColor = value; save(); }, v -> v.displayName())
                        .describe("Boss color while its beacon is on the ground waiting to be "
                                + "broken. Click to cycle."),
                SettingRow.enumOptions("Phase: Hits", () -> cfg().phaseHitsColor,
                        value -> { cfg().phaseHitsColor = value; save(); }, v -> v.displayName())
                        .describe("Boss color during the 'hits' phase, when only melee hits count. "
                                + "Click to cycle."),

                SettingRow.label("— Zombie Slayer (Revenant) —"),
                SettingRow.toggle("Enrage Alert", () -> cfg().revEnrageAlert,
                        () -> { cfg().revEnrageAlert = !cfg().revEnrageAlert; save(); })
                        .describe("Flashes a warning when the boss changes state: tier 3/4 going "
                                + "Mad and then Enraged (faster, hits much harder - back off or "
                                + "burst it), and tier 5 charging up its huge blast."),
                SettingRow.toggle("Highlight Thrown TNT", () -> cfg().revTntHighlight,
                        () -> { cfg().revTntHighlight = !cfg().revTntHighlight; save(); })
                        .describe("Draws a box around the TNT the tier 5 boss throws at the circle "
                                + "under your feet. Every TNT that hits you heals the boss half a "
                                + "million health, so the box shows you what to step away from."),
                SettingRow.enumOptions("TNT Color", () -> cfg().revTntColor,
                        value -> { cfg().revTntColor = value; save(); }, v -> v.displayName())
                        .describe("The color of the TNT boxes and their alert. Click to cycle."),
                SettingRow.toggle("TNT Alert", () -> cfg().revTntAlert,
                        () -> { cfg().revTntAlert = !cfg().revTntAlert; save(); })
                        .describe("Flashes TNT! while one is in the air near your boss, so you move "
                                + "even when you were looking at the boss instead of the ground."),

                SettingRow.label("— Spider Slayer (Tarantula) —"),
                SettingRow.toggle("Highlight Egg Sacs", () -> cfg().taraEggHighlight,
                        () -> { cfg().taraEggHighlight = !cfg().taraEggHighlight; save(); })
                        .describe("Draws a box around the egg sacs the boss lays at 66% and 33% "
                                + "health. The boss wraps itself in a cocoon and cannot be hurt "
                                + "until every sac is broken - and sacs left alone hatch. The boxes "
                                + "are what let you find all of them fast."),
                SettingRow.enumOptions("Egg Color", () -> cfg().taraEggColor,
                        value -> { cfg().taraEggColor = value; save(); }, v -> v.displayName())
                        .describe("The color of the egg-sac boxes and their alert. Click to cycle."),
                SettingRow.toggle("Egg Alert", () -> cfg().taraEggAlert,
                        () -> { cfg().taraEggAlert = !cfg().taraEggAlert; save(); })
                        .describe("Flashes EGGS! when the egg phase starts, so you stop hitting the "
                                + "(now invulnerable) boss and go break the sacs instead."),

                SettingRow.label("— Wolf Slayer (Sven) —"),
                SettingRow.toggle("Highlight Pups", () -> cfg().svenPupHighlight,
                        () -> { cfg().svenPupHighlight = !cfg().svenPupHighlight; save(); })
                        .describe("Draws a box around every Sven Pup. At half health the boss calls "
                                + "them and is protected until they all die, so the pups ARE the "
                                + "fight for that stretch - the boxes keep them apart from the "
                                + "ordinary wolves around you."),
                SettingRow.enumOptions("Pup Color", () -> cfg().svenPupColor,
                        value -> { cfg().svenPupColor = value; save(); }, v -> v.displayName())
                        .describe("The color of the pup boxes and their alert. Click to cycle."),
                SettingRow.toggle("Pup Alert", () -> cfg().svenPupAlert,
                        () -> { cfg().svenPupAlert = !cfg().svenPupAlert; save(); })
                        .describe("Flashes PUPS! when the boss calls its pups, so you switch "
                                + "targets right away instead of whacking a protected boss."),

                SettingRow.label("— All Slayers —"),
                SettingRow.enumOptions("Boss State Color", () -> cfg().bossStateColor,
                        value -> { cfg().bossStateColor = value; save(); }, v -> v.displayName())
                        .describe("The boss box switches to this color while the boss is in a "
                                + "special state: Revenant Mad/Enraged or charging, Sven protected "
                                + "behind its pups, Tarantula cocooned during the egg phase. Needs "
                                + "Boss Phase Colors on. Click to cycle."),
                SettingRow.toggle("Miniboss Alert", () -> cfg().minibossAlert,
                        () -> { cfg().minibossAlert = !cfg().minibossAlert; save(); })
                        .describe("Flashes MINIBOSS above your crosshair when a slayer miniboss "
                                + "spawns near you. Minibosses can drop the same rare items as the "
                                + "boss, so they are worth turning for."),
                SettingRow.toggle("Miniboss Highlight", () -> cfg().minibossHighlight,
                        () -> { cfg().minibossHighlight = !cfg().minibossHighlight; save(); })
                        .describe("Draws a colored box around every slayer miniboss near you, so "
                                + "they stand out from the ordinary mobs around them."),
                SettingRow.enumOptions("Miniboss Color", () -> cfg().minibossColor,
                        value -> { cfg().minibossColor = value; save(); }, v -> v.displayName())
                        .describe("The color of the miniboss boxes and their alert. Click to cycle."),
                SettingRow.toggle("Miniboss Pointer Line", () -> cfg().minibossTracer,
                        () -> { cfg().minibossTracer = !cfg().minibossTracer; save(); })
                        .anchor("miniboss_tracer")
                        .describe("Draws a line from your crosshair to every highlighted miniboss, "
                                + "so you can turn straight onto one that spawned behind you. "
                                + "Independent of the module-wide Show Pointer Lines switch."),
                SettingRow.label("Keep them boxed during the boss fight, per slayer:"),
                SettingRow.toggle("Revenant Minibosses In Fight", () -> cfg().minibossInFightRevenant,
                        () -> { cfg().minibossInFightRevenant = !cfg().minibossInFightRevenant; save(); })
                        .describe("Keeps Sycophants, Champions, Deformed and Atoned Revenants boxed "
                                + "after the Revenant boss is up. Off by default: before the spawn a "
                                + "miniboss is the thing worth turning for, during the fight it is "
                                + "usually one more box competing with the boss's own. The Miniboss "
                                + "Alert is unaffected either way."),
                SettingRow.toggle("Tarantula Minibosses In Fight", () -> cfg().minibossInFightTarantula,
                        () -> { cfg().minibossInFightTarantula = !cfg().minibossInFightTarantula; save(); })
                        .describe("Keeps Tarantula Vermin, Beasts and Mutant Tarantulas boxed "
                                + "after the Tarantula boss is up. Off by default: before the spawn a "
                                + "miniboss is the thing worth turning for, during the fight it is "
                                + "usually one more box competing with the boss's own. The Miniboss "
                                + "Alert is unaffected either way."),
                SettingRow.toggle("Sven Minibosses In Fight", () -> cfg().minibossInFightSven,
                        () -> { cfg().minibossInFightSven = !cfg().minibossInFightSven; save(); })
                        .describe("Keeps Pack Enforcers, Sven Followers and Sven Alphas boxed "
                                + "after the Sven boss is up. Off by default: before the spawn a "
                                + "miniboss is the thing worth turning for, during the fight it is "
                                + "usually one more box competing with the boss's own. The Miniboss "
                                + "Alert is unaffected either way."),
                SettingRow.toggle("Voidgloom Minibosses In Fight", () -> cfg().minibossInFightVoidgloom,
                        () -> { cfg().minibossInFightVoidgloom = !cfg().minibossInFightVoidgloom; save(); })
                        .describe("Keeps Voidling Devotees, Radicals and Voidcrazed Maniacs boxed "
                                + "after the Voidgloom boss is up. Off by default: before the spawn a "
                                + "miniboss is the thing worth turning for, during the fight it is "
                                + "usually one more box competing with the boss's own. The Miniboss "
                                + "Alert is unaffected either way."),
                SettingRow.toggle("Inferno Minibosses In Fight", () -> cfg().minibossInFightInferno,
                        () -> { cfg().minibossInFightInferno = !cfg().minibossInFightInferno; save(); })
                        .describe("Keeps Flare, Kindleheart and Burningsoul Demons boxed "
                                + "after the Inferno boss is up. Off by default: before the spawn a "
                                + "miniboss is the thing worth turning for, during the fight it is "
                                + "usually one more box competing with the boss's own. The Miniboss "
                                + "Alert is unaffected either way."),
                SettingRow.label("§8The Riftstalker has no minibosses, so it has no row"),
                SettingRow.toggle("Only My Minibosses", () -> cfg().minibossOwnOnly,
                        () -> { cfg().minibossOwnOnly = !cfg().minibossOwnOnly; save(); })
                        .describe("On, only the minibosses from your own fight are shown - the ones "
                                + "that spawned at your boss. Off shows every miniboss in the "
                                + "lobby, which is what you want when you are hunting them for "
                                + "drops rather than fighting your own quest."),
                SettingRow.label("Off = the whole lobby's minibosses, not just yours"),
                SettingRow.label("§8Yours = spawned at your boss; minibosses carry no owner line"),
                SettingRow.toggle("Only With Active Quest", () -> cfg().minibossOnlyWithQuest,
                        () -> { cfg().minibossOnlyWithQuest = !cfg().minibossOnlyWithQuest; save(); })
                        .describe("On, minibosses are only shown while you have a slayer quest "
                                + "running - otherwise everyone else's spawns would light up too. "
                                + "Turn it off to hunt minibosses without a quest of your own."),
                SettingRow.label("Off = every miniboss in the area, quest or not"),
                SettingRow.label("§7Carries: enable it in the Slayer Carry Counter"),

                SettingRow.label("— Blaze —"),
                SettingRow.toggle("Fire Pillar Alert", () -> cfg().pillarAlert,
                        () -> { cfg().pillarAlert = !cfg().pillarAlert; save(); })
                        .describe("Counts down the Blaze boss's fire pillar above your crosshair "
                                + "during its last 5 seconds. If the pillar is not broken in time, "
                                + "everyone fighting the boss dies - this makes the timer "
                                + "impossible to miss."),
                SettingRow.toggle("Show Needed Attunement", () -> cfg().attunementDisplay,
                        () -> { cfg().attunementDisplay = !cfg().attunementDisplay; save(); })
                        .describe("Shows the shield mode your Blaze fight needs right now (Ashen, "
                                + "Spirit, Auric or Crystal) above the crosshair, plus the mode "
                                + "your dagger is actually on - green when they match, red when "
                                + "you still have to swap. Wrong mode means your hits do almost "
                                + "nothing."),
                SettingRow.label("The dagger mode your Inferno fight needs right now"),
                SettingRow.button("Move / Resize Attunement", () -> open(new HudEditorScreen(
                        new HudElement[] {HudElement.BLAZE_ATTUNEMENT}, "Edit Blaze Attunement")))
                        .describe("Opens the editor where you drag the attunement text anywhere on "
                                + "the screen and scale it."),
                SettingRow.toggle("Attunement Swap Alert", () -> cfg().attunementAlert,
                        () -> { cfg().attunementAlert = !cfg().attunementAlert; save(); })
                        .describe("Flashes SWAP: <mode> with a ping every time the boss's shield "
                                + "changes, i.e. every time you have to flip or switch your dagger."),
                SettingRow.toggle("Highlight Correct Dagger", () -> cfg().daggerHighlight,
                        () -> { cfg().daggerHighlight = !cfg().daggerHighlight; save(); })
                        .describe("Puts a yellow box around the hotbar slot holding the dagger the "
                                + "fight needs right now, so you never grab the wrong one of the "
                                + "two."),
                SettingRow.label("Yellow box on the hotbar slot to switch to"),

                SettingRow.label("— Vampire —"),
                SettingRow.toggle("Twinclaws Alert", () -> cfg().twinclawsAlert,
                        () -> { cfg().twinclawsAlert = !cfg().twinclawsAlert; save(); })
                        .describe("Flashes TWINCLAWS above your crosshair when the Vampire boss "
                                + "starts its Twinclaws strike, so you know to dodge or heal."),
                SettingRow.toggle("Highlight Ichor / Spring", () -> cfg().ichorHighlight,
                        () -> { cfg().ichorHighlight = !cfg().ichorHighlight; save(); })
                        .describe("Draws boxes around the Blood Ichor (red) and Killer Spring "
                                + "(green) markers during the Vampire fight - the spots you have "
                                + "to stand at or destroy for the fight mechanics."),
                SettingRow.toggle("Show Pointer Lines", () -> cfg().showTracers,
                        () -> { cfg().showTracers = !cfg().showTracers; save(); })
                        .anchor("show_tracers")
                        .describe("Draws a thin line from your crosshair to every slayer highlight "
                                + "(boss, beacon, miniboss, ichor...), so you can follow the line "
                                + "when the target itself is behind you or out of view. The beacon "
                                + "and the nukekubi skulls have their own switches above if you "
                                + "only want lines for those."));
    }

    private static void open(net.minecraft.client.gui.screens.Screen screen) {
        net.minecraft.client.Minecraft.getInstance().setScreenAndShow(screen);
    }
}
