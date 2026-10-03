/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.diana;

import sbs.modid.client.combat.diana.model.MythCreature;
import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.core.alert.AlertChannelRows;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.ArrayList;
import java.util.List;

/**
 * Diana (Combat): the Mythological Ritual - burrows found, guesses fitted, chains counted, rare
 * creatures called out.
 *
 * <p>The runtime lives in {@code logic/} and {@code render/}; this class is the settings page and
 * nothing else. Self-registered via {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 *
 * <h2>Ships off, and the page says why</h2>
 *
 * <p>Two independent reasons, and the player should meet both before switching it on. It paints the
 * world and can be made to write into party chat, which is a thing to opt into rather than to
 * discover. And <b>nothing behind it has been verified against this client</b>: every particle shape
 * it matches, the box it casts rays inside, the curve it extrapolates along and the four creature
 * names it watches for all came from a description of a different client generation. The page says
 * so, and the debug switch is what turns one session in game into a correction.
 *
 * <h2>What it deliberately will not do</h2>
 *
 * <p>Two of this feature's obvious conveniences are missing on purpose, and the settings that stand
 * where they would be say as much: the Sphinx's answer is marked and never sent, and the best warp
 * is named and never taken. Both would be the mod playing the game rather than showing it, which is
 * the line the whole project is built on. See {@code SPEC_DIANA.md} section 8.
 */
public final class DianaModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public DianaModule() {
    }

    @Override
    public String id() {
        return "diana";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.COMBAT;
    }

    @Override
    public String displayName() {
        return "Diana";
    }

    @Override
    public String description() {
        return "Mythological Ritual: burrows, guesses, chains and rare creatures";
    }

    @Override
    public int accentColor() {
        return 0xFFFFD65A;
    }

    private static SBSConfig.DianaSettings cfg() {
        return ConfigManager.getInstance().get().diana;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        List<SettingRow> rows = new ArrayList<>();

        rows.add(SettingRow.toggle("Diana Toolkit", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                .describe("Everything below, in the Hub, while Diana holds the perk as Mayor or "
                        + "Minister - or while Jerry has borrowed it and you are carrying a spade. "
                        + "Off everywhere else, so it costs nothing the rest of the year."));

        rows.add(SettingRow.label("§8Unverified against this client - see the Debug rows below."));

        // --- Burrow detection ---
        rows.add(SettingRow.label("§bBurrows"));

        rows.add(SettingRow.toggle("Detect Nearby Burrows", () -> cfg().detectBurrows,
                        () -> { cfg().detectBurrows = !cfg().detectBurrows; save(); })
                .describe("Reads the particles Hypixel draws over a burrow and remembers where it "
                        + "is and what kind it is. Everything else here is built on this."));

        rows.add(SettingRow.toggle("Burrow Waypoints", () -> cfg().burrowWaypoints,
                        () -> { cfg().burrowWaypoints = !cfg().burrowWaypoints; save(); })
                .describe("Marks each known burrow in the world with its distance."));

        rows.add(SettingRow.toggle("Show Burrow Kind", () -> cfg().showBurrowKind,
                        () -> { cfg().showBurrowKind = !cfg().showBurrowKind; save(); })
                .describe("Puts Start, Mob or Treasure on the marker instead of just \"Burrow\". A "
                        + "burrow whose kind particle has not arrived yet still reads \"Burrow\" - "
                        + "the label never guesses."));

        rows.add(SettingRow.color("Start Burrow Colour", () -> cfg().startColorHex, () -> 0xFF7FD9FF,
                        () -> openPicker("Start Burrow", () -> cfg().startColorHex,
                                hex -> cfg().startColorHex = hex))
                .describe("The head of a chain."));

        rows.add(SettingRow.color("Mob Burrow Colour", () -> cfg().mobColorHex, () -> 0xFFFF8A6B,
                        () -> openPicker("Mob Burrow", () -> cfg().mobColorHex,
                                hex -> cfg().mobColorHex = hex))
                .describe("Digging it spawns something that has to be dealt with."));

        rows.add(SettingRow.color("Treasure Burrow Colour", () -> cfg().treasureColorHex,
                        () -> 0xFFFFD65A,
                        () -> openPicker("Treasure Burrow", () -> cfg().treasureColorHex,
                                hex -> cfg().treasureColorHex = hex))
                .describe("Digging it pays out."));

        // --- Guesses ---
        rows.add(SettingRow.label("§bGuesses"));

        rows.add(SettingRow.toggle("Spade Guess", () -> cfg().spadeGuess,
                        () -> { cfg().spadeGuess = !cfg().spadeGuess; save(); })
                .describe("Fits the arc the spade's ability draws and marks where it lands. Needs "
                        + "the particles to arrive, so Hypixel's own particle quality setting has "
                        + "to be high enough to send them."));

        rows.add(SettingRow.toggle("Arrow Guess", () -> cfg().arrowGuess,
                        () -> { cfg().arrowGuess = !cfg().arrowGuess; save(); })
                .describe("Fits the arrow drawn out of a burrow you just dug and casts it at the "
                        + "ground to find the next one. The better of the two - it costs no "
                        + "ability and it is drawn every time."));

        rows.add(SettingRow.toggle("Show Alternative Guesses", () -> cfg().showAlternativeGuesses,
                        () -> { cfg().showAlternativeGuesses = !cfg().showAlternativeGuesses; save(); })
                .describe("An arrow cast down a long axis genuinely has several equally good "
                        + "answers. On, you see all of them and walk them in order. Off, you see "
                        + "the first and the rest step forward silently as it is ruled out."));

        rows.add(SettingRow.toggle("Label Alternatives", () -> cfg().labelAlternatives,
                        () -> { cfg().labelAlternatives = !cfg().labelAlternatives; save(); })
                .describe("Writes \"Possible\" on the runners-up rather than leaving them as faint "
                        + "unnamed markers."));

        rows.add(SettingRow.color("Guess Colour", () -> cfg().guessColorHex, () -> 0xFF9BE37F,
                        () -> openPicker("Guess", () -> cfg().guessColorHex,
                                hex -> cfg().guessColorHex = hex))
                .describe("Kept distinct from the burrow colours on purpose: a guess and a burrow "
                        + "must never look the same."));

        rows.add(SettingRow.toggle("Suggested Warp", () -> cfg().suggestWarp,
                        () -> { cfg().suggestWarp = !cfg().suggestWarp; save(); })
                .describe("Names the Hub warp that lands nearest the guess, under the marker. It "
                        + "only ever names it - bind the warp to a key yourself under Command "
                        + "Keybinds if you want one press. The mod does not travel for you."));

        // --- Prompts ---
        rows.add(SettingRow.label("§bPrompts"));

        rows.add(SettingRow.toggle("Prompt When a Guess Fails", () -> cfg().promptOnGuessFailure,
                        () -> { cfg().promptOnGuessFailure = !cfg().promptOnGuessFailure; save(); })
                .describe("Says so when the arrow could not be read, so a silent screen is not "
                        + "mistaken for the toolkit still thinking."));

        rows.add(SettingRow.toggle("Prompt When a Chain Ends", () -> cfg().promptOnChainEnd,
                        () -> { cfg().promptOnChainEnd = !cfg().promptOnChainEnd; save(); })
                .describe("Only when there is genuinely nothing left nearby - a chain ending next "
                        + "to three other burrows is not worth announcing."));

        rows.add(SettingRow.rangeSlider("Nearby Means", 20, 300, () -> cfg().chainEndRadius,
                        v -> { cfg().chainEndRadius = v; save(); }, " blocks")
                .describe("How far a burrow or guess may be and still count as something to walk "
                        + "to, for the prompt above."));

        rows.addAll(AlertChannelRows.forAlert("diana_prompt", "the spade prompts",
                () -> cfg().promptChannels, mask -> { cfg().promptChannels = mask; save(); }));

        rows.add(SettingRow.toggle("Running Chains Card", () -> cfg().chainsHud,
                        () -> { cfg().chainsHud = !cfg().chainsHud; save(); })
                .describe("How many chains are running and how long the oldest has left. Counted "
                        + "from chat, so a client that joined mid-event counts low."));

        // --- Creatures ---
        rows.add(SettingRow.label("§bMythological creatures"));

        rows.add(SettingRow.label("§8These four names are in none of our data - see Debug."));

        rows.add(SettingRow.toggle("Creature Health Card", () -> cfg().creatureHealthHud,
                        () -> { cfg().creatureHealthHud = !cfg().creatureHealthHud; save(); })
                .describe("Health of the rare creatures in sight, read off their nametags."));

        rows.add(SettingRow.rangeSlider("Low Health Alert", 0, 100, () -> cfg().lowHealthMillions,
                        v -> { cfg().lowHealthMillions = v; save(); }, "M")
                .describe("Alerts once when a rare creature drops below this much health. 0 is "
                        + "off."));

        rows.add(SettingRow.toggle("Missing Shuriken Warning", () -> cfg().shurikenWarning,
                        () -> { cfg().shurikenWarning = !cfg().shurikenWarning; save(); })
                .describe("Warns while a rare creature in sight has no shuriken applied."));

        rows.addAll(AlertChannelRows.forAlert("diana_creature", "rare creature alerts",
                () -> cfg().creatureAlertChannels,
                mask -> { cfg().creatureAlertChannels = mask; save(); }));

        for (MythCreature creature : MythCreature.values()) {
            rows.add(SettingRow.toggle("Watch " + creature.defaultName(),
                            () -> cfg().watchedCreatures.contains(creature.name()),
                            () -> {
                                if (!cfg().watchedCreatures.remove(creature.name())) {
                                    cfg().watchedCreatures.add(creature.name());
                                }
                                save();
                            })
                    .describe("Alerts, markers and sharing for this one."));
        }

        for (MythCreature creature : MythCreature.values()) {
            rows.add(SettingRow.text(creature.defaultName() + " Name", creature.defaultName(), 48,
                            () -> cfg().creatureNames.getOrDefault(creature.name(), ""),
                            value -> {
                                if (value == null || value.isBlank()) {
                                    cfg().creatureNames.remove(creature.name());
                                } else {
                                    cfg().creatureNames.put(creature.name(), value.trim());
                                }
                                save();
                            })
                    .describe("What this creature's nametag actually says, if the default is "
                            + "wrong. Matched loosely, so the bare name is enough. Empty uses \""
                            + creature.defaultName() + "\"."));
        }

        rows.add(SettingRow.toggle("Box Rare Creatures",
                        sbs.modid.client.combat.diana.logic.CreatureHighlight::active,
                        () -> {
                            if (sbs.modid.client.combat.diana.logic.CreatureHighlight.active()) {
                                sbs.modid.client.combat.diana.logic.CreatureHighlight.disable();
                            } else {
                                sbs.modid.client.combat.diana.logic.CreatureHighlight.enable();
                            }
                            save();
                        })
                .describe("Draws a box around the watched creatures, through the Mob Highlight "
                        + "module rather than a second highlighter of its own - so they appear in "
                        + "that module's list and keep its colour and its line-of-sight rule. "
                        + "Switching this on also switches Mob Highlight on; switching it off "
                        + "removes only these four and leaves your other picks alone."));

        rows.add(SettingRow.toggle("Mark Creatures You Can See", () -> cfg().markVisibleCreatures,
                        () -> { cfg().markVisibleCreatures = !cfg().markVisibleCreatures; save(); })
                .describe("Marks a rare creature already on your screen. Only ones in plain sight: "
                        + "a marker on something behind a hill would be information you did not "
                        + "have, which is not what this mod is for."));

        rows.add(SettingRow.toggle("Waypoint Shared Coordinates", () -> cfg().receiveSharedCreatures,
                        () -> { cfg().receiveSharedCreatures = !cfg().receiveSharedCreatures; save(); })
                .describe("Turns \"x: 12, y: 70, z: -40 | inq\" from your party into a marker. "
                        + "Party and coop only - a guild is not standing in this Hub."));

        rows.add(SettingRow.rangeSlider("Creature Marker Lasts", 10, 300,
                        () -> cfg().creatureMarkerSeconds,
                        v -> { cfg().creatureMarkerSeconds = v; save(); }, "s")
                .describe("How long a shared or spotted creature stays marked."));

        rows.add(SettingRow.toggle("Announce Spawns to Party", () -> cfg().announceSpawnsToParty,
                        () -> { cfg().announceSpawnsToParty = !cfg().announceSpawnsToParty; save(); })
                .describe("Posts your coordinates and the creature to party chat when one spawns. "
                        + "Off by default because it puts words in your mouth in front of other "
                        + "people. Once per creature, never repeated."));

        rows.add(SettingRow.text("Announce Format", "{creature}", 64,
                        () -> cfg().announceFormat,
                        v -> { cfg().announceFormat = v; save(); })
                .describe("What follows the coordinates. {creature} is replaced. The coordinates "
                        + "and the [SBS] tag are added around it."));

        // --- Trackers ---
        rows.add(SettingRow.label("§bTrackers"));

        rows.add(SettingRow.toggle("Burrow and Loot Tracker", () -> cfg().tracker,
                        () -> { cfg().tracker = !cfg().tracker; save(); })
                .describe("Counts burrows, creatures and loot. Lifetime totals go to the shared "
                        + "tracker store with everything else."));

        rows.add(SettingRow.toggle("Session Totals Card", () -> cfg().sessionHud,
                        () -> { cfg().sessionHud = !cfg().sessionHud; save(); })
                .describe("This session's counts on the Diana card, under the chains."));

        // --- Chat ---
        rows.add(SettingRow.label("§bChat"));

        rows.add(SettingRow.toggle("Mark the Sphinx's Answer", () -> cfg().sphinxAnswers,
                        () -> { cfg().sphinxAnswers = !cfg().sphinxAnswers; save(); })
                .describe("Prints which of the three answers is correct. Fifteen riddles are known; "
                        + "a reworded one is logged rather than guessed at."));

        rows.add(SettingRow.toggle("Click Anywhere to Answer", () -> cfg().sphinxClickToAnswer,
                        () -> { cfg().sphinxClickToAnswer = !cfg().sphinxClickToAnswer; save(); })
                .describe("With the chat open, a left-click anywhere gives the marked answer "
                        + "instead of you having to click the answer itself. Armed only for half a "
                        + "minute after a riddle is solved, once per riddle, and never retried. Off "
                        + "by default: this is the one place the mod turns a click you did not aim "
                        + "into a command. CTRL + click still copies a message."));

        rows.add(SettingRow.toggle("Hide Ritual Chatter", () -> cfg().hideRitualChatter,
                        () -> { cfg().hideRitualChatter = !cfg().hideRitualChatter; save(); })
                .describe("Hides the arrow hint, the ability cooldown line, \"Warping...\" and "
                        + "\"There are blocks in the way\". Only those four - anything this build "
                        + "does not fully recognise is passed through untouched."));

        // --- Debug ---
        rows.add(SettingRow.label("§bDebug"));

        rows.add(SettingRow.label("§8Nothing here has been seen working on this client."));

        rows.add(SettingRow.toggle("Log Unrecognised Signals", () -> cfg().debugLog,
                        () -> { cfg().debugLog = !cfg().debugLog; save(); })
                .describe("Logs every particle and chat line that nearly matched and was refused, "
                        + "under [SBS][Diana]. This is how the toolkit gets corrected: the shapes "
                        + "that show up here are what the game is really sending."));

        rows.add(SettingRow.label("§8/sbs diana - what it currently believes and why"));
        rows.add(SettingRow.label("§8/sbs diana clear - forget every burrow, guess and chain"));
        rows.add(SettingRow.label("§8/sbs particleprobe arm - a full capture to a file"));

        return rows;
    }

    /**
     * Opens the shared colour picker on one of this module's four colours.
     *
     * <p>Four marker colours, one helper, because the picker takes a getter and a setter and the
     * only thing that differs between the rows is which field they name. Clearing the colour in the
     * picker hands the marker back to the global waypoint preset, which is what an empty hex means
     * everywhere else in the mod.
     */
    private static void openPicker(String label, java.util.function.Supplier<String> current,
                                   java.util.function.Consumer<String> setter) {
        net.minecraft.client.gui.screens.Screen previous =
                GuiStateManager.getInstance().getCurrentScreen();
        net.minecraft.client.Minecraft.getInstance().setScreenAndShow(
                new sbs.modid.client.ui.theme.ThemeColorPickerScreen(
                        "Diana  •  " + label, current.get(),
                        value -> {
                            setter.accept(value == null ? "" : value);
                            save();
                        }, previous));
    }
}
