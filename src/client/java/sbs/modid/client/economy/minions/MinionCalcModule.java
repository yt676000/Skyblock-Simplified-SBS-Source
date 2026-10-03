/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.minions;

import net.minecraft.client.Minecraft;
import sbs.modid.client.core.alert.AlertChannelRows;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.economy.minions.logic.MinionCatalogs;
import sbs.modid.client.economy.minions.model.MinionData;
import sbs.modid.client.economy.minions.ui.MinionCalcScreen;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.ArrayList;
import java.util.List;

/**
 * Minion Calculator (Economy): every minion ranked by coins or skill XP per slot per day, from
 * the AH / Bazaar data the mod already caches - projections under stated, editable assumptions.
 * Opened with {@code /sbs minions}, its hotkey, or the button below.
 * Self-registered via {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 */
public final class MinionCalcModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public MinionCalcModule() {
    }

    @Override
    public String id() {
        return "minion_calc";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.ECONOMY;
    }

    @Override
    public String displayName() {
        return "Minion Calculator";
    }

    @Override
    public String description() {
        return "Minions ranked by coins and skill XP per slot per day, under your assumptions";
    }

    @Override
    public int accentColor() {
        return 0xFF6BBF59;
    }

    private static SBSConfig.MinionCalcSettings cfg() {
        return ConfigManager.getInstance().get().minionCalc;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        List<SettingRow> rows = new ArrayList<>(32);
        rows.addAll(List.of(
                SettingRow.toggle("Minion Calculator", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("Ranks every minion by projected coins per day, skill XP per "
                                + "day, or both - per minion slot, at its top tier, priced from "
                                + "the Bazaar and item data the mod already caches. Every figure "
                                + "is a projection under the assumptions shown in the screen "
                                + "(collection interval, fuel, hopper, compactor, extra speed), "
                                + "and each one is editable right there.")
                        .inDevelopment(),
                SettingRow.label(catalogSummary()),

                SettingRow.intField("Collection Interval", 1, 720,
                        () -> cfg().intervalHours,
                        value -> { cfg().intervalHours = value; save(); }, "h")
                        .describe("How often you actually empty your minions. A full minion "
                                + "stops producing, so this caps every projected rate - the "
                                + "single assumption that changes results the most. 24 means "
                                + "once a day."),
                SettingRow.intField("Extra Skill XP (Wisdom)", 0, 300,
                        () -> cfg().xpBoostPct,
                        value -> { cfg().xpBoostPct = value; save(); }, "%")
                        .describe("Added to every XP figure, for Wisdom or event boosts you "
                                + "actually run. XP is granted when you collect, so hopper-sold "
                                + "items never count."),

                SettingRow.intField("Community Shop Slots Bought", 0, 5,
                        () -> cfg().communitySlots,
                        value -> { cfg().communitySlots = value; save(); }, "")
                        .describe("How many of the five Community Shop minion-slot upgrades this "
                                + "profile has. Only used to derive your slot count until the "
                                + "Crafted Minions menu has been opened once - that menu states "
                                + "the limit outright and then wins."),
                SettingRow.label(capturedSummary())));

        rows.addAll(stoppedRows());

        rows.addAll(List.of(
                SettingRow.keybind("Open Calculator", () -> cfg().openKey,
                        key -> { cfg().openKey = key; save(); })
                        .describe("A key that opens the minion calculator in-game. "
                                + "/sbs minions does the same."),
                SettingRow.button("Open Minion Calculator", () ->
                        Minecraft.getInstance().setScreenAndShow(new MinionCalcScreen()))
                        .describe("Opens the ranking now.")
                        .inDevelopment(),
                SettingRow.button("Open Optimizer", () ->
                        Minecraft.getInstance().setScreenAndShow(
                                new sbs.modid.client.economy.minions.ui.MinionPlanScreen()))
                        .describe("Given a budget, your slots and an objective, solves for the "
                                + "best complete setup - which minions, tiers, fuel and upgrades "
                                + "- as an exact knapsack, not a greedy list. It recommends; it "
                                + "never buys, crafts or places anything. /sbs minions plan does "
                                + "the same.")
                        .inDevelopment(),
                SettingRow.button("Forget Witnessed Minion State", () ->
                        sbs.modid.client.economy.minions.logic.MinionStateStore.getInstance().reset())
                        .describe("Clears this profile's captured records: crafted tiers, the "
                                + "slot limit, placed minions and GUI configurations. They fill "
                                + "back in as you open the menus and visit your island.")));
        return rows;
    }

    /**
     * The stopped-minion warning's own rows.
     *
     * <p>Kept together and separate from the calculator's: the calculator projects what a minion
     * <i>would</i> earn, this reports what one is <i>doing</i>. They share a page because they
     * share a subject and a scan, not because they are the same feature.
     */
    private static List<SettingRow> stoppedRows() {
        List<SettingRow> rows = new ArrayList<>(12);
        rows.add(SettingRow.toggle("Stopped Minion Warning", () -> cfg().stoppedWarning,
                        () -> { cfg().stoppedWarning = !cfg().stoppedWarning; save(); })
                .describe("Reads the line written over each minion on your island and tells you "
                        + "when one has stopped - storage full, or no room to work. Default: off, "
                        + "see the note below.")
                .anchor("minion_stopped_enabled"));
        rows.add(SettingRow.label("§8Off by default: the exact words Hypixel writes over a stopped"));
        rows.add(SettingRow.label("§8minion have never been recorded, so the ones below are a guess."));
        rows.add(SettingRow.label("§8Everything found over a minion is written to the log as"));
        rows.add(SettingRow.label("§f[SBS][Minions]§8, which is how you replace the guess with the truth."));
        rows.add(SettingRow.label("§8Status: " + stoppedSummary()));

        rows.add(SettingRow.toggle("Mark Them In The World", () -> cfg().stoppedWorldMarks,
                        () -> { cfg().stoppedWorldMarks = !cfg().stoppedWorldMarks; save(); })
                .describe("Draws a box and the reason over each stopped minion while you are on "
                        + "your island. Red for full, orange for blocked. Default: on.")
                .anchor("minion_stopped_world_marks"));
        rows.add(SettingRow.text("Words For \"Full\"",
                        sbs.modid.client.economy.minions.logic.MinionStopRules.DEFAULT_FULL_WORDS,
                        sbs.modid.client.core.config.share.ShareValues.MAX_TEXT,
                        () -> cfg().stoppedFullWords,
                        value -> { cfg().stoppedFullWords = value; save(); })
                .describe("Comma-separated pieces of the line that mean the storage is full. "
                        + "Matched anywhere in the text and case is ignored, so short and "
                        + "distinctive beats long and exact.")
                .anchor("minion_stopped_full_words"));
        rows.add(SettingRow.text("Words For \"Blocked\"",
                        sbs.modid.client.economy.minions.logic.MinionStopRules.DEFAULT_BLOCKED_WORDS,
                        sbs.modid.client.core.config.share.ShareValues.MAX_TEXT,
                        () -> cfg().stoppedBlockedWords,
                        value -> { cfg().stoppedBlockedWords = value; save(); })
                .describe("The same, for a minion that cannot place or reach what it needs. "
                        + "Checked after the full words, so a line mentioning both counts as full.")
                .anchor("minion_stopped_blocked_words"));
        rows.add(SettingRow.toggle("Flag Anything Else Too", () -> cfg().stoppedFlagUnknown,
                        () -> { cfg().stoppedFlagUnknown = !cfg().stoppedFlagUnknown; save(); })
                .describe("Treat any other line above a minion as a stoppage. §eOff by default§r: "
                        + "a decorative nametag would otherwise be reported as a broken minion. "
                        + "Worth switching on only while you are hunting the real wording. "
                        + "Default: off.")
                .anchor("minion_stopped_flag_unknown"));
        rows.addAll(AlertChannelRows.forAlert("minion_stopped", "a minion stops",
                () -> cfg().stoppedChannels,
                value -> { cfg().stoppedChannels = value; save(); }));
        return rows;
    }

    /** What the last island scan found, with its age - never presented as a live figure. */
    private static String stoppedSummary() {
        var store = sbs.modid.client.economy.minions.logic.MinionStateStore.getInstance();
        if (!cfg().stoppedWarning) {
            return "§7switched off";
        }
        long at = store.stoppedAt();
        if (at == 0) {
            return "§7no island scan yet";
        }
        int stopped = store.stopped().size();
        int full = store.stoppedCount(
                sbs.modid.client.economy.minions.model.MinionStopReason.FULL);
        String what = stopped == 0 ? "§anothing stopped"
                : "§f" + sbs.modid.client.economy.minions.logic.MinionStopRules.summary(stopped, full);
        long minutes = (System.currentTimeMillis() - at) / 60_000L;
        String age = minutes < 1 ? "just now"
                : minutes < 60 ? minutes + " min ago" : (minutes / 60) + " h ago";
        return what + " §7(as of " + age + ")";
    }

    /** What has actually been witnessed for this profile - the delta mode is only as good as this. */
    private static String capturedSummary() {
        var store = sbs.modid.client.economy.minions.logic.MinionStateStore.getInstance();
        if (store.craftedReadAt() == 0 && store.scanAt() == 0) {
            return "§8Nothing captured yet - open /craftedminions and visit your island";
        }
        String unknown = store.unknownPlaced() > 0 ? " (+" + store.unknownPlaced() + " skinned)" : "";
        return "§8" + store.uniqueCraftCount() + " unique tier(s) crafted · "
                + store.placedTotal() + " placed seen" + unknown
                + (store.minionsLimit() > 0 ? " · limit " + store.minionsLimit() : "");
    }

    /** What the ranking is computed from - data version and coverage at a glance. */
    private static String catalogSummary() {
        MinionData data = MinionCatalogs.minions();
        if (data == null) {
            return "§cMinion catalog failed to load";
        }
        return "§8" + data.minions.size() + " minion types, catalog v" + data.dataVersion
                + " · prices from the shared Bazaar cache";
    }
}
