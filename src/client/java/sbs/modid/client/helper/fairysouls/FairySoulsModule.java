/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.fairysouls;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.ModuleSubgroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.helper.fairysouls.logic.FairySoulDatabase;
import sbs.modid.client.helper.fairysouls.logic.FairySoulLearned;
import sbs.modid.client.helper.fairysouls.logic.FairySoulRouting;
import sbs.modid.client.helper.fairysouls.logic.FairySoulStore;
import sbs.modid.client.helper.fairysouls.logic.FairySoulTracker;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.ArrayList;
import java.util.List;

/**
 * Fairy Souls module: markers for every soul on the island, and routing to the nearest uncollected
 * one by <b>route cost</b>.
 *
 * <p>Self-registered via {@code META-INF/services/sbs.modid.client.core.module.SbsModule}; settings in
 * {@link SBSConfig.FairySoulSettings}, coordinates in {@link FairySoulDatabase}, per-profile progress
 * in {@link FairySoulStore}.
 *
 * <p>Off by default: the local collection record starts empty, and for an established player that
 * means the markers are not trustworthy until they have reconciled - see the status rows below.
 */
public final class FairySoulsModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public FairySoulsModule() {
    }

    @Override
    public String id() {
        return "fairy_souls";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.QUALITY_OF_LIFE;
    }

    @Override
    public ModuleSubgroup subgroup() {
        return ModuleSubgroup.NAVIGATION;
    }

    @Override
    public String displayName() {
        return "Fairy Souls";
    }

    @Override
    public String description() {
        return "Marks every Fairy Soul on the island and routes to the nearest uncollected";
    }

    @Override
    public int accentColor() {
        return 0xFFE07AD8;
    }

    private static SBSConfig.FairySoulSettings cfg() {
        return ConfigManager.getInstance().get().fairySouls;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        List<SettingRow> rows = new ArrayList<>(List.of(
                SettingRow.toggle("Fairy Souls", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("Marks every catalogued Fairy Soul on the island you are on. Off "
                                + "draws nothing and records nothing."),
                SettingRow.label("Markers for every soul on this island"),

                SettingRow.toggle("Route To Nearest", () -> cfg().pathfind,
                        () -> { cfg().pathfind = !cfg().pathfind; save(); })
                        .describe("Draws a walking route to the nearest uncollected soul. Nearest "
                                + "by ROUTE, not by straight line - one twenty blocks away through "
                                + "a wall is further than one sixty blocks down a corridor. "
                                + "Collect it and the route moves to the next by itself."),
                SettingRow.label("Nearest by route cost, and it retargets as you collect"),

                SettingRow.toggle("This Island Only", () -> cfg().currentIslandOnly,
                        () -> { cfg().currentIslandOnly = !cfg().currentIslandOnly; save(); })
                        .describe("Keeps targeting to the island you are standing on. Routing "
                                + "across islands needs the inter-island travel edges, which are "
                                + "not built yet - until then this is on in effect either way."),

                SettingRow.toggle("Show Collected", () -> cfg().showCollected,
                        () -> { cfg().showCollected = !cfg().showCollected; save(); })
                        .describe("Keeps drawing souls already on record as collected, dimmed. Off "
                                + "by default: a soul you have found disappears the moment it is "
                                + "found, which is the only way the field ever empties out."),
                SettingRow.toggle("Pointer Lines", () -> cfg().showTracers,
                        () -> { cfg().showTracers = !cfg().showTracers; save(); })
                        .anchor("tracers")
                        .describe("Draws a line from your crosshair to each uncollected soul."),
                SettingRow.toggle("Through Walls", () -> cfg().throughWalls,
                        () -> { cfg().throughWalls = !cfg().throughWalls; save(); })
                        .describe("Keeps markers visible with terrain in the way."),
                SettingRow.toggle("Show Distance", () -> cfg().showDistance,
                        () -> { cfg().showDistance = !cfg().showDistance; save(); })
                        .describe("Puts the distance in each marker's label."),
                SettingRow.toggle("Show Hints", () -> cfg().showHints,
                        () -> { cfg().showHints = !cfg().showHints; save(); })
                        .describe("Puts a second line under the soul being routed to: its zone, and "
                                + "a warning when it cannot be walked to. Only that one - printing "
                                + "it under every marker would bury the field."),

                SettingRow.enumOptions("Uncollected Colour", () -> cfg().uncollectedColor,
                        value -> {
                            cfg().uncollectedColor = value;
                            cfg().uncollectedColorHex = "";
                            save();
                        }, v -> v.displayName()),
                SettingRow.rangeSlider("Uncollected Opacity", 10, 100, () -> cfg().uncollectedOpacity,
                        value -> { cfg().uncollectedOpacity = value; save(); }, "%"),
                SettingRow.enumOptions("Collected Colour", () -> cfg().collectedColor,
                        value -> {
                            cfg().collectedColor = value;
                            cfg().collectedColorHex = "";
                            save();
                        }, v -> v.displayName()),
                SettingRow.rangeSlider("Collected Opacity", 0, 100, () -> cfg().collectedOpacity,
                        value -> { cfg().collectedOpacity = value; save(); }, "%"),
                SettingRow.enumOptions("Pointer Line Colour", () -> cfg().tracerColor,
                        value -> {
                            cfg().tracerColor = value;
                            cfg().tracerColorHex = "";
                            save();
                        }, v -> v.displayName())
                        .anchor("tracer_colour"),
                SettingRow.rangeSlider("Pointer Line Opacity", 10, 100, () -> cfg().tracerOpacity,
                        value -> { cfg().tracerOpacity = value; save(); }, "%")
                        .anchor("tracer_opacity")));

        // ---------------------------------------------------------------- record state
        rows.add(SettingRow.label("§8—— Collection record ——"));
        rows.add(SettingRow.label("§8" + FairySoulTracker.getInstance().reconciliation()));
        if (!FairySoulTracker.getInstance().questLogRead()) {
            rows.add(SettingRow.label("§eOpen the Quest Log's Fairy Souls page once."));
            rows.add(SettingRow.label("§8It states how many souls you have found per island, which "
                    + "is what retires the markers on every island you have already finished. "
                    + "Reading it happens by itself while this module is on."));
        } else if (FairySoulTracker.getInstance().recordIncomplete()) {
            rows.add(SettingRow.label("§eThe game reports more collected here than is tracked."));
            rows.add(SettingRow.label("§8Souls found before this mod cannot be known individually "
                    + "- Hypixel publishes counts, never identities. The markers on this island "
                    + "will include some you already have."));
        }
        rows.add(SettingRow.button("Mark This Island Collected",
                () -> FairySoulTracker.getInstance().markCurrentIslandDone(true))
                .describe("Treats every soul on the island you are standing on as collected, "
                        + "without inventing per-soul records. The honest fix for a profile that "
                        + "was played before this mod existed."));
        rows.add(SettingRow.button("Un-mark This Island",
                () -> FairySoulTracker.getInstance().markCurrentIslandDone(false))
                .describe("Undoes the above for the island you are on."));
        rows.add(SettingRow.button("Back To Nearest",
                () -> FairySoulRouting.getInstance().clearManual())
                .describe("Drops a hand-picked target and returns to auto-nearest."));

        rows.add(SettingRow.label("§8" + FairySoulRouting.getInstance().statusLine()));
        rows.add(SettingRow.label("§8" + islandLine()));
        rows.add(SettingRow.label("§8Data: " + FairySoulDatabase.status()));
        rows.add(SettingRow.label("§8" + FairySoulLearned.getInstance().status()));
        rows.add(SettingRow.label("§8A soul is only recorded when exactly one catalogued "
                + "coordinate is in reach - never on a guess."));
        return List.copyOf(rows);
    }

    /** "Hub: 4 of 80 collected · Quest Log: 61/80" for the island underfoot. */
    private static String islandLine() {
        String island = SkyBlockLocation.island();
        if (island.isEmpty()) {
            return "Not on a known island";
        }
        int total = FairySoulDatabase.forIsland(island).size();
        if (total == 0) {
            return island + ": no coordinates in the data file yet";
        }
        StringBuilder out = new StringBuilder(island).append(": ")
                .append(FairySoulTracker.getInstance().collectedHereCount(island))
                .append(" of ").append(total).append(" collected");
        FairySoulStore store = FairySoulStore.getInstance();
        if (store.isIslandDone(island)) {
            out.append(" (complete)");
        }
        FairySoulStore.Progress progress = store.menuProgress(island);
        if (progress != null) {
            out.append("  ·  Quest Log: ").append(progress.found).append('/').append(progress.total);
            // Coverage, not progress: the guide's denominator is the island's real soul count, so a
            // smaller catalogue is this build's data file being behind Hypixel, said plainly.
            if (progress.total > total) {
                out.append(" (").append(progress.total - total).append(" not catalogued yet)");
            }
        }
        return out.toString();
    }
}
