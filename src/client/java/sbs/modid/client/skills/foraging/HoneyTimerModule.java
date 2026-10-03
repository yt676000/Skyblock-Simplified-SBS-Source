/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.foraging;

import sbs.modid.client.core.alert.AlertChannelRows;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.ModuleSubgroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.skills.SkillIslands;
import sbs.modid.client.skills.foraging.logic.HoneyTimerStore;
import sbs.modid.client.skills.foraging.logic.HoneyTreeTimers;
import sbs.modid.client.skills.foraging.model.HoneyItems;
import sbs.modid.client.social.chat.logic.SBSChat;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.ui.HudEditorScreen;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.ArrayList;
import java.util.List;

/**
 * Honey module (Skills): the smear cooldown on Galatea's protected honey trees.
 *
 * <p>Self-registered via {@code META-INF/services/sbs.modid.client.core.module.SbsModule}; every
 * option writes to {@link SBSConfig.HoneySettings} and is read live, so changes apply without a
 * restart.
 *
 * <p><b>Ships off, and the page says why.</b> Neither the Honeycomb item id nor the chat wording is
 * in any dataset this repository holds, so the detection is built on a hypothesis - root
 * {@code AGENTS.md} requires such a feature to be off by default and to say so where the player can
 * read it, which is what the labels at the top of this page do.
 *
 * <p><b>Informational only.</b> Nothing here clicks, smears or plays the game: it watches a
 * right-click the player made themselves and counts down from it.
 */
public final class HoneyTimerModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public HoneyTimerModule() {
    }

    @Override
    public String id() {
        return "honey_timer";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.SKILLS;
    }

    @Override
    public ModuleSubgroup subgroup() {
        return ModuleSubgroup.FORAGING;
    }

    @Override
    public String displayName() {
        return "Honey";
    }

    @Override
    public String description() {
        return "Times the cooldown on each honey tree you smear, and shows which are ready again";
    }

    @Override
    public int accentColor() {
        return 0xFFFFD65A;
    }

    private static SBSConfig.HoneySettings cfg() {
        return ConfigManager.getInstance().get().honey;
    }

    /** The island gate is Foraging's, shared with every other Galatea module. */
    private static SBSConfig.ForagingSettings foraging() {
        return ConfigManager.getInstance().get().foraging;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        List<SettingRow> rows = new ArrayList<>(32);

        rows.add(SettingRow.toggle("Honey Tree Timer", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                .describe("Starts a timer for the honey tree you smear with Honeycomb, and "
                        + "remembers which tree it was. Timers survive a relog, a server hop and an "
                        + "island change, because they count from the clock rather than from ticks. "
                        + "Default: off - see the note below.")
                .inDevelopment());
        rows.add(SettingRow.label("§8Detection is unverified: neither Hypixel's Honeycomb id nor the"));
        rows.add(SettingRow.label("§8message it prints is in any data we hold. It logs §f[SBS][Honey]§8 with"));
        rows.add(SettingRow.label("§8what it really sees, and §f/sbs chatprobe arm§8 records the lines verbatim."));
        rows.add(SettingRow.label("§8Status: " + HoneyTreeTimers.getInstance().status()));

        // ---------------------------------------------------------------- duration
        rows.add(SettingRow.rangeSlider("Cooldown", 1, 60,
                        () -> cfg().durationMinutes,
                        value -> { cfg().durationMinutes = value; save(); }, "min")
                .describe("How long a smeared tree stays on cooldown. §eThis number is estimated, "
                        + "not measured§r - nobody has timed a real one, so every remaining time "
                        + "shown is only as right as this. Changing it corrects the timers already "
                        + "running, unless one of them was synced from the world. Default: 15 min.")
                .anchor("honey_duration"));
        rows.add(SettingRow.toggle("Sync From The World", () -> cfg().syncFromWorld,
                        () -> { cfg().syncFromWorld = !cfg().syncFromWorld; save(); })
                .describe("If Hypixel shows a countdown above the tree, believe that instead of our "
                        + "own clock. §eOff by default because no such countdown has been seen§r - "
                        + "the reader is written against Hypixel's usual nametag pattern and has "
                        + "never met a honey tree. Whatever stands above a tracked tree is written "
                        + "to the log either way, which is how you find out whether this is worth "
                        + "switching on. Default: off.")
                .anchor("honey_sync_world"));

        // ---------------------------------------------------------------- detection
        rows.add(SettingRow.rangeSlider("Snap To A Tree Within", 4, 24,
                        () -> cfg().toleranceBlocks,
                        value -> { cfg().toleranceBlocks = value; save(); }, "m")
                .describe("How far the block you clicked may be from one of the shipped honey tree "
                        + "positions and still count as that tree. A tree is many blocks wide and "
                        + "the shipped position is one point in it. Nothing within this range means "
                        + "the timer is registered where you clicked instead, and the log names the "
                        + "position so the data can be fixed. Default: 9m.")
                .anchor("honey_tolerance"));
        rows.add(SettingRow.rangeSlider("Wait For Confirmation", 1, 15,
                        () -> cfg().confirmWindowSeconds,
                        value -> { cfg().confirmWindowSeconds = value; save(); }, "s")
                .describe("How long a click waits for Hypixel to say the smear worked before the "
                        + "setting below decides what happens. Default: 4s.")
                .anchor("honey_confirm_window"));
        rows.add(SettingRow.toggle("Start Without A Confirmation", () -> cfg().startWithoutConfirmation,
                        () -> { cfg().startWithoutConfirmation = !cfg().startWithoutConfirmation; save(); })
                .describe("Start the timer even when no message we recognised arrived. §eOn by "
                        + "default, because the wording is a guess§r: switching this off would make "
                        + "the feature do nothing at all until somebody confirms the message, which "
                        + "looks exactly like it being broken. A timer started this way is drawn "
                        + "with a §f?§r, so a guess is never shown as a fact. Default: on.")
                .anchor("honey_start_unconfirmed"));
        rows.add(SettingRow.text("Honeycomb Ids", HoneyItems.DEFAULT_IDS, 200,
                        () -> cfg().honeycombIds,
                        value -> { cfg().honeycombIds = value; save(); })
                .describe("Comma-separated pieces of the SkyBlock item id that mean \"this is "
                        + "Honeycomb\". Matched as substrings, so keep them specific - §eCOMB§r on "
                        + "its own also matches Recombobulator and every Combat talisman. The "
                        + "vanilla honeycomb item and an item named Honeycomb are always "
                        + "recognised as well. §f/sbs honey held§r says what you are holding and "
                        + "whether it would start a timer.")
                .anchor("honey_item_ids"));
        rows.add(SettingRow.text("Message Keywords", SBSConfig.HoneySettings.DEFAULT_WORDS, 200,
                        () -> cfg().chatWords,
                        value -> { cfg().chatWords = value; save(); })
                .describe("Comma-separated words that mark a chat line as \"the smear worked\". A "
                        + "line another player typed is never accepted, whatever it contains. Lines "
                        + "arriving while a click is waiting and matching none of these are written "
                        + "to the log, which is how you find the real wording.")
                .anchor("honey_chat_words"));

        // ---------------------------------------------------------------- display
        rows.add(SettingRow.toggle("Show On The Waypoint", () -> cfg().showOnWaypoint,
                        () -> { cfg().showOnWaypoint = !cfg().showOnWaypoint; save(); })
                .describe("Puts the remaining time under the tree's marker, amber while it runs and "
                        + "green once it is ready. Needs the honey tree waypoint group switched on "
                        + "in Galatea Waypoints - this draws on those markers, it does not make new "
                        + "ones. Default: on.")
                .anchor("honey_show_waypoint"));
        rows.add(SettingRow.toggle("Show The Card", () -> cfg().showHud,
                        () -> { cfg().showHud = !cfg().showHud; save(); })
                .describe("A movable card listing every timer, readiest first, with the island and "
                        + "the tree. It disappears entirely when nothing is running. Default: on.")
                .anchor("honey_show_hud"));
        rows.add(SettingRow.button("Move / Resize Card", () -> open(new HudEditorScreen(
                        new HudElement[] {HudElement.HONEY_TIMERS}, "Edit Honey Timers")))
                .describe("Opens the editor where you drag the card anywhere on the screen and "
                        + "scale it."));
        rows.add(SettingRow.toggle("Only On The Island It Belongs To", () -> cfg().onlyOnHoneyIslands,
                        () -> { cfg().onlyOnHoneyIslands = !cfg().onlyOnHoneyIslands; save(); })
                .describe("Hides timers for trees on an island you are not standing on. They keep "
                        + "running either way - this only decides whether you are looking at them. "
                        + "Default: on.")
                .anchor("honey_only_relevant_island"));

        // ---------------------------------------------------------------- notification
        rows.add(SettingRow.toggle("Message When A Timer Starts", () -> cfg().startMessage,
                        () -> { cfg().startMessage = !cfg().startMessage; save(); })
                .describe("One line in chat naming the tree and how long it has, each time a smear "
                        + "starts a timer. Default: on.")
                .anchor("honey_start_message"));
        rows.add(SettingRow.rangeSlider("Warn Before It Is Ready", 0, 300,
                        () -> cfg().preWarningSeconds,
                        value -> { cfg().preWarningSeconds = value; save(); }, "s")
                .describe("An early heads-up this many seconds before a tree comes back. 0 switches "
                        + "it off. Default: 60s.")
                .anchor("honey_pre_warning"));
        rows.addAll(AlertChannelRows.forAlert("honey_ready", "a honey tree is ready",
                () -> cfg().notifyChannels,
                value -> { cfg().notifyChannels = value; save(); }));
        rows.add(SettingRow.label("§8Trees finishing together are announced in one line, never one each"));

        // ---------------------------------------------------------------- housekeeping
        rows.add(SettingRow.rangeSlider("Forget A Finished Timer After", 5, 360,
                        () -> cfg().pruneAfterMinutes,
                        value -> { cfg().pruneAfterMinutes = value; save(); }, "min")
                .describe("How long a tree stays on the list after it is ready again, before it is "
                        + "dropped. Default: 60 min.")
                .anchor("honey_prune_after"));

        rows.add(SettingRow.toggle("Only on Galatea", () -> foraging().islandLock,
                        () -> { foraging().islandLock = !foraging().islandLock; save(); })
                .describe("Keeps the foraging features to the islands they are about. This one "
                        + "switch is shared by every Galatea module. Default: on."));
        rows.add(SettingRow.label("§8" + SkillIslands.describe(SkillIslands.FORAGING_ISLANDS)));

        rows.add(SettingRow.button("What Am I Holding?",
                        () -> SBSChat.send(HoneyTreeTimers.getInstance().describeHeld()))
                .describe("Prints the item in your main hand, its SkyBlock id, and whether it would "
                        + "start a timer. The fastest way to find out that Hypixel's Honeycomb is "
                        + "not called what we guessed."));
        rows.add(SettingRow.button("Clear All Timers", () -> {
                    int removed = HoneyTimerStore.getInstance().clear();
                    SBSChat.send(removed == 0 ? "No honey timers to clear."
                            : "Cleared " + removed + " honey timer(s).");
                })
                .describe("Forgets every timer on this profile. For starting over after a wrong one "
                        + "was recorded."));
        return rows;
    }

    private static void open(net.minecraft.client.gui.screens.Screen screen) {
        net.minecraft.client.Minecraft.getInstance().setScreenAndShow(screen);
    }
}
