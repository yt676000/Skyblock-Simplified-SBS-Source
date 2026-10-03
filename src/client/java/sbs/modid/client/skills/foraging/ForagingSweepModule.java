/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.foraging;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.ModuleSubgroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.skills.SkillIslands;
import sbs.modid.client.skills.foraging.model.SweepStaleMode;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.ui.HudEditorScreen;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.List;

/**
 * Sweep HUD module (Skills): the effective Sweep Hypixel prints for each chop, on a movable card.
 *
 * <p>Self-registered via {@code META-INF/services/sbs.modid.client.core.module.SbsModule}; every
 * option writes to {@link SBSConfig.SweepSettings} and is read live, so changes apply without a
 * restart.
 */
public final class ForagingSweepModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public ForagingSweepModule() {
    }

    @Override
    public String id() {
        return "foraging_sweep";
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
        return "Sweep";
    }

    @Override
    public String description() {
        return "Shows the Sweep that really applied to your last chop, not the stat menu's number";
    }

    @Override
    public int accentColor() {
        return 0xFF57D977;
    }

    private static SBSConfig.SweepSettings cfg() {
        return ConfigManager.getInstance().get().sweep;
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
        return List.of(
                SettingRow.toggle("Sweep Card", () -> cfg().enabled,
                                () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("Shows the Sweep that actually applied to the chop you just made. "
                                + "The stat menu leaves out every conditional bonus - melee-only "
                                + "bonuses, the first hit on a tree, tree-specific bonuses and the "
                                + "thrown-axe penalty - so the real number is only ever published in "
                                + "the per-chop message. Default: on.")
                        .inDevelopment(),
                SettingRow.label("§8Reads Hypixel's per-chop foraging lines - it never asks for them"),

                SettingRow.toggle("Show Toughness", () -> cfg().showToughness,
                                () -> { cfg().showToughness = !cfg().showToughness; save(); })
                        .describe("Adds the tree's toughness to the card, when the message carries "
                                + "it. Default: on."),
                SettingRow.toggle("Show Blocks Per Chop", () -> cfg().showBlocks,
                                () -> { cfg().showBlocks = !cfg().showBlocks; save(); })
                        .describe("Adds how many blocks the chop took, when the message carries it. "
                                + "Default: on."),
                SettingRow.toggle("Show Session Max & Average", () -> cfg().showSessionStats,
                                () -> { cfg().showSessionStats = !cfg().showSessionStats; save(); })
                        .describe("Adds the highest and the average Sweep seen since you arrived. "
                                + "Both reset when you change island, world or profile. Default: off."),

                SettingRow.enumOptions("When There Is No Recent Chop", () -> cfg().staleMode,
                                value -> { cfg().staleMode = value; save(); },
                                SweepStaleMode::displayName)
                        .describe("What the card does once nothing has been chopped for a while. "
                                + "Grey out keeps the last number but marks it as not live, Hide "
                                + "takes the card away until the next chop, Keep showing leaves it "
                                + "as it is. Default: Grey out."),
                SettingRow.rangeSlider("Counts As Recent For", 2, 60,
                                () -> cfg().staleAfterSeconds,
                                value -> { cfg().staleAfterSeconds = value; save(); }, "s")
                        .describe("How long a chop stays live before the setting above applies. "
                                + "Has no effect while the setting above is Keep showing, which "
                                + "never greys out. Default: 10s.")
                        .disabledIf(cfg().staleMode == SweepStaleMode.KEEP),

                SettingRow.toggle("Only While Chopping", () -> cfg().requireRecentChop,
                                () -> { cfg().requireRecentChop = !cfg().requireRecentChop; save(); })
                        .describe("Hides the card unless a log actually came down in the last few "
                                + "seconds, so it is not sitting on your screen while you walk "
                                + "around. This follows your Foraging XP, which means it counts a "
                                + "thrown axe as well as a swung one. Default: off.")
                        .anchor("sweep_require_recent_chop"),
                SettingRow.rangeSlider("Keep Up After A Chop", 5, 120,
                                () -> cfg().recentChopSeconds,
                                value -> { cfg().recentChopSeconds = value; save(); }, "s")
                        .describe("How long a felled log keeps the card on screen. Default: 20s.")
                        .disabledIf(!cfg().requireRecentChop),

                SettingRow.toggle("Only With An Axe In Hand", () -> cfg().requireAxeInHand,
                                () -> { cfg().requireAxeInHand = !cfg().requireAxeInHand; save(); })
                        .describe("Hides the card unless you are holding an axe, in either hand - "
                                + "the offhand counts, because a thrown axe leaves your main hand "
                                + "empty. Default: off.")
                        .anchor("sweep_require_axe"),

                SettingRow.toggle("Only on Galatea", () -> foraging().islandLock,
                                () -> { foraging().islandLock = !foraging().islandLock; save(); })
                        .describe("Keeps the foraging features to the islands they are about. This "
                                + "one switch is shared by every Galatea module. Default: on."),
                SettingRow.label("§8" + SkillIslands.describe(SkillIslands.FORAGING_ISLANDS)),

                SettingRow.toggle("Hide The Lines In Chat", () -> cfg().hideChatMessages,
                                () -> { cfg().hideChatMessages = !cfg().hideChatMessages; save(); })
                        .describe("Takes the per-chop lines out of chat once the card is showing "
                                + "them. Only lines the card actually understood are ever hidden - "
                                + "a wording we cannot read stays in chat, so you never lose "
                                + "information silently. Default: off."),

                SettingRow.button("Move / Resize Card", () -> open(new HudEditorScreen(
                                new HudElement[] {HudElement.FORAGING_SWEEP}, "Edit Sweep Card")))
                        .describe("Opens the editor where you drag the Sweep card anywhere on the "
                                + "screen and scale it."),

                SettingRow.label("§8The message wording is not confirmed yet. If the card stays"),
                SettingRow.label("§8empty while chopping, run §f/sbs sweep capture§8 and send the file"),
                SettingRow.button("Show The Setup Hint Again", () -> {
                            cfg().sweepHintShown = false;
                            save();
                        })
                        .describe("Re-arms the one-time hint that explains how to switch Hypixel's "
                                + "per-chop foraging lines on. It appears once, while you are "
                                + "chopping with no lines arriving."));
    }

    private static void open(net.minecraft.client.gui.screens.Screen screen) {
        net.minecraft.client.Minecraft.getInstance().setScreenAndShow(screen);
    }
}
