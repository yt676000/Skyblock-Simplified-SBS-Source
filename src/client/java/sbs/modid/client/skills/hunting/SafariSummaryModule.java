/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.hunting;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.ModuleSubgroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.core.util.NumberDisplay;
import sbs.modid.client.skills.hunting.logic.SafariTracker;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.ui.HudEditorScreen;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.ArrayList;
import java.util.List;

/**
 * Safari Summary module (Skills): when you come back out of the Safari Zone, it tells you what the
 * trip gave you - every shard you caught in there, with counts and what they are worth.
 *
 * <p>The whole point is that nothing else can answer that afterwards: shards go straight to the
 * Hunting Box, which only ever shows a lifetime total, and the chat lines naming each catch are gone
 * by the time you are back. Counting and the trip boundaries live in {@link SafariTracker}, the card
 * in {@link sbs.modid.client.skills.hunting.render.SafariSummaryHud}; this class registers the module
 * and its rows. Self-registered via
 * {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 */
public final class SafariSummaryModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public SafariSummaryModule() {
    }

    @Override
    public String id() {
        return "safari_summary";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.SKILLS;
    }

    @Override
    public ModuleSubgroup subgroup() {
        return ModuleSubgroup.HUNTING;
    }

    @Override
    public String displayName() {
        return "Safari Summary";
    }

    @Override
    public String description() {
        return "Coming back from the Safari Zone: every shard that trip caught, with counts and value";
    }

    @Override
    public int accentColor() {
        return 0xFFE0A14D;
    }

    private static SBSConfig.SafariSettings cfg() {
        return ConfigManager.getInstance().get().safari;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        List<SettingRow> rows = new ArrayList<>();
        rows.add(SettingRow.toggle("Safari Summary", () -> cfg().enabled,
                () -> { cfg().enabled = !cfg().enabled; save(); })
                .describe("Counts every shard you catch inside the Safari Zone and shows the whole "
                        + "trip as one summary the moment you leave. Shards go straight to the "
                        + "Hunting Box, so this is the only place that run is ever added up."));
        rows.add(SettingRow.label("§8Counted from the catch lines - Hypixel announces every shard"));

        rows.add(SettingRow.text("Zone Name", SafariTracker.DEFAULT_ZONE, 32,
                () -> cfg().zoneName == null ? "" : cfg().zoneName,
                v -> { cfg().zoneName = v == null ? "" : v.trim(); save(); })
                .describe("The word the trip is recognised by, matched against the scoreboard's ⏣ "
                        + "line and the island name, upper/lower case ignored. Leave it empty for "
                        + "\"" + SafariTracker.DEFAULT_ZONE + "\". Change it if Hypixel renames the "
                        + "area - then the summary keeps working without an update."));
        rows.add(SettingRow.label("§8Currently in: " + where()));

        rows.add(SettingRow.toggle("Chat Summary", () -> cfg().chatSummary,
                () -> { cfg().chatSummary = !cfg().chatSummary; save(); })
                .describe("Prints the trip into chat as well: one line per shard kind plus the "
                        + "total. Stays scrollable long after the card is gone."));
        rows.add(SettingRow.toggle("Summary Card", () -> cfg().hudSummary,
                () -> { cfg().hudSummary = !cfg().hudSummary; save(); })
                .describe("Shows the same summary as a card on screen when you come out."));
        rows.add(SettingRow.intField("Card Time", 3, 300, () -> cfg().hudSeconds,
                value -> { cfg().hudSeconds = value; save(); }, "s")
                .describe("How long the summary card stays on screen after a trip ends."));

        rows.add(SettingRow.toggle("Show Values", () -> cfg().showValues,
                () -> { cfg().showValues = !cfg().showValues; save(); })
                .describe("Adds what each shard is worth at the Bazaar instasell price - and the "
                        + "trip's total - next to the counts. Off leaves plain counts."));
        rows.add(SettingRow.toggle("Live Counter", () -> cfg().liveCounter,
                () -> { cfg().liveCounter = !cfg().liveCounter; save(); })
                .describe("Keeps the same card on screen while you are still inside, counting "
                        + "along. Off means the card only ever appears once you are back out."));
        rows.add(SettingRow.toggle("Sound", () -> cfg().sound,
                () -> { cfg().sound = !cfg().sound; save(); })
                .describe("A short ping when the summary appears."));

        rows.add(SettingRow.button("Move / Resize Card", () -> open(new HudEditorScreen(
                new HudElement[] {HudElement.SAFARI_SUMMARY}, "Edit Safari Summary")))
                .describe("Opens the editor where you drag the summary card anywhere on the screen "
                        + "and scale it."));
        rows.add(SettingRow.label(status()));
        rows.add(SettingRow.label("§8All-time totals are kept in config/sbs/tracker/safaritracker.txt"));
        return List.copyOf(rows);
    }

    /** Where the location reader thinks you are - the fastest way to check the zone name matches. */
    private static String where() {
        String live = sbs.modid.client.core.location.SkyBlockLocation.describe();
        return SafariTracker.getInstance().inside() ? live + " §a(counting)" : live;
    }

    /** The last finished trip as one line, so the page can be checked without going back in. */
    private static String status() {
        SafariTracker.Summary summary = SafariTracker.getInstance().lastSummary();
        if (summary == null || summary.isEmpty()) {
            return "No trip recorded yet this session";
        }
        String line = "Last trip: " + summary.totalShards() + " shards in "
                + summary.entries().size() + " kinds, " + SafariTracker.duration(summary.durationMs());
        return summary.value() > 0
                ? line + " · " + NumberDisplay.format(summary.value())
                : line;
    }

    private static void open(net.minecraft.client.gui.screens.Screen screen) {
        net.minecraft.client.Minecraft.getInstance().setScreenAndShow(screen);
    }
}
