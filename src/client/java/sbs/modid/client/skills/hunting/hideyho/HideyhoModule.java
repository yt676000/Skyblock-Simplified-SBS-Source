/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.hunting.hideyho;

import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.ModuleSubgroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.core.render.OverlayColor;
import sbs.modid.client.skills.hunting.hideyho.logic.HideyhoSpots;
import sbs.modid.client.skills.hunting.hideyho.logic.HideyhoTracker;
import sbs.modid.client.ui.settings.SettingRow;
import sbs.modid.client.ui.theme.ThemeColorPickerScreen;

import java.util.ArrayList;
import java.util.List;

/**
 * Hideyho Finder module (Skills): the walk between the places the Hideyho hides, while a round of
 * its hide-and-seek is running.
 *
 * <p><b>It never draws the critter, and nothing it draws goes through a wall.</b> That was the
 * request and the mechanic agrees with it: the moment the critter hides it is somewhere else in the
 * biome, and a marker shining through the terrain would answer the question the round is asking
 * instead of helping the player search. What is drawn is the search itself - a marker on each known
 * hiding place, occluded like any other block in the world, crossed off as the player looks at it,
 * with the pathfinder leading to whichever is nearest by route.
 *
 * <p><b>The spot list starts empty, and fills itself.</b> No coordinates are shipped: the lists that
 * exist belong to a wiki under CC BY-SA and to another mod, and neither is ours to copy. Every
 * completed round ends with the critter in front of the player, so each find is a confirmed hiding
 * place - see {@link HideyhoSpots}. The page says so rather than leaving a player to wonder why a
 * fresh install draws nothing.
 *
 * <p>The round, the crossing-off and the publishing live in {@link HideyhoTracker}; there is no
 * drawing code in this package at all - the shared waypoint renderer draws the markers and the
 * pathfinder draws the route. Self-registered via
 * {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 */
public final class HideyhoModule implements SbsModule {

    /** The shipped marker colour, used as the swatch fallback and the picker's starting point. */
    private static final int DEFAULT_COLOR = 0xFF4DFFC3;

    /** ServiceLoader needs a public no-arg constructor. */
    public HideyhoModule() {
    }

    @Override
    public String id() {
        return "hideyho_finder";
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
        return "Hideyho Finder";
    }

    @Override
    public String description() {
        return "Walks you round the places " + HideyhoTracker.CRITTER
                + " hides - markers and a route, never a box through the walls";
    }

    @Override
    public int accentColor() {
        return DEFAULT_COLOR;
    }

    private static SBSConfig.HideyhoSettings cfg() {
        return ConfigManager.getInstance().get().hideyho;
    }

    private static void save() {
        ConfigManager.getInstance().save();
        HideyhoTracker.getInstance().refresh();
    }

    @Override
    public List<SettingRow> settings() {
        List<SettingRow> rows = new ArrayList<>();

        rows.add(SettingRow.toggle("Hideyho Finder", () -> cfg().enabled,
                () -> { cfg().enabled = !cfg().enabled; save(); })
                .describe("While a round of " + HideyhoTracker.CRITTER + "'s hide-and-seek is "
                        + "running, marks every hiding place you have found it in before and leads "
                        + "you round them. The critter itself is never highlighted and no marker is "
                        + "drawn through a wall - the point is the search, not being told the "
                        + "answer."));
        rows.add(SettingRow.label("§8Starts and ends on what the critter says in chat"));

        rows.add(SettingRow.toggle("Route To The Next Spot", () -> cfg().pathfind,
                () -> { cfg().pathfind = !cfg().pathfind; save(); })
                .describe("Hands the unchecked spots to the pathfinder together, so the path leads "
                        + "to whichever is nearest by route - not the nearest in a straight line "
                        + "through a cliff. Off leaves the markers and the walking to you."));
        rows.add(SettingRow.toggle("Show Spots Outside A Round", () -> cfg().showAlways,
                () -> { cfg().showAlways = !cfg().showAlways; save(); })
                .describe("Keeps the markers up between rounds as well, for learning where the "
                        + "hiding places are. Off means they appear when a round starts and go when "
                        + "it ends."));

        rows.add(SettingRow.color("Marker Colour", () -> cfg().colorHex, () -> DEFAULT_COLOR,
                HideyhoModule::openColorPicker)
                .describe("The colour of the spot markers and their beams."));
        rows.add(SettingRow.toggle("Show Distance", () -> cfg().showDistance,
                () -> { cfg().showDistance = !cfg().showDistance; save(); })
                .describe("Adds how far away each spot is under its label."));

        rows.add(SettingRow.intField("Checked-Off Range", 1, 32, () -> cfg().arriveRadius,
                value -> { cfg().arriveRadius = value; save(); }, "m")
                .describe("How close you have to get for a spot to count as looked at. It also "
                        + "needs a clear line to it - a spot behind a wall you never saw is not "
                        + "crossed off however close you walked past it."));
        rows.add(SettingRow.intField("Round Ceiling", 30, 1800, () -> cfg().roundSeconds,
                value -> { cfg().roundSeconds = value; save(); }, "s")
                .describe("How long a round may run before the markers are dropped. Hypixel's own "
                        + "timeout is unknown, so this is a ceiling rather than a reading of it."));
        rows.add(SettingRow.toggle("Chat Lines", () -> cfg().announce,
                () -> { cfg().announce = !cfg().announce; save(); })
                .describe("One line when a round starts, ends or times out - including whether the "
                        + "place you found it in was a new one."));

        rows.add(SettingRow.label("§8—— The Biome Filter ——"));
        rows.add(SettingRow.toggle("Only In One Biome", () -> cfg().restrictToBiome,
                () -> { cfg().restrictToBiome = !cfg().restrictToBiome; save(); })
                .describe("Restricts the markers to the biome named below. Off by default because "
                        + "nobody has checked whether the Haunted biome is its own ⏣ zone at all - "
                        + "switching this on before it is could make the finder silently dark."));
        rows.add(SettingRow.text("Biome Word", "Haunted", 32,
                () -> cfg().biomeWord == null ? "" : cfg().biomeWord,
                v -> { cfg().biomeWord = v == null ? "" : v.trim(); save(); })
                .describe("Matched against the ⏣ zone line, upper/lower case ignored. Change it if "
                        + "Hypixel names the biome something else - then the filter keeps working "
                        + "without an update."));
        rows.add(SettingRow.label("§8You are: " + SkyBlockLocation.describe()));

        rows.add(SettingRow.label("§8—— Hiding Places ——"));
        rows.add(SettingRow.label(spotsLine()));
        rows.add(SettingRow.label("§8Nothing is shipped: each spot is one you found the critter in."));
        rows.add(SettingRow.button("Forget All Spots", () -> {
            HideyhoSpots.getInstance().clear();
            HideyhoTracker.getInstance().refresh();
        }).describe("Empties the learned list. Only worth doing if Hypixel moves the hiding places "
                + "and the old ones send you the wrong way - the list rebuilds itself from the "
                + "next finds."));
        rows.add(SettingRow.label("§8Kept in config/sbs/data/hideyho_spots.json"));

        rows.add(SettingRow.label("§8Now: " + HideyhoTracker.getInstance().status()));
        rows.add(SettingRow.label(payoutLine()));
        rows.add(SettingRow.label("§eThe chat lines this runs on come from a wiki, not from a"));
        rows.add(SettingRow.label("§8capture - off by default for that reason. While it is on, every"));
        rows.add(SettingRow.label("§8line the critter says that no pattern matched is written to the"));
        rows.add(SettingRow.label("§8log as [SBS][Hideyho], so one round settles the real wording."));
        return List.copyOf(rows);
    }

    /** How many hiding places are known, in the words a player reads the empty state in. */
    private static String spotsLine() {
        int known = HideyhoSpots.getInstance().size();
        return known == 0
                ? "§eNo hiding places known yet - find " + HideyhoTracker.CRITTER
                        + " once and it is remembered"
                : "§7" + known + " hiding place(s) learned so far";
    }

    /** The last round's payout, which is also the evidence the chat patterns actually fired. */
    private static String payoutLine() {
        String payout = HideyhoTracker.getInstance().lastPayout();
        return payout.isEmpty()
                ? "§8No round has finished this session"
                : "§7Last round: " + payout;
    }

    /** Opens the shared colour picker on the current colour, saving whatever comes back. */
    private static void openColorPicker() {
        String current = cfg().colorHex == null || cfg().colorHex.isBlank()
                ? OverlayColor.toHex(DEFAULT_COLOR)
                : cfg().colorHex;
        GuiStateManager state = GuiStateManager.getInstance();
        net.minecraft.client.Minecraft.getInstance().setScreenAndShow(new ThemeColorPickerScreen(
                "Hideyho Finder  •  Markers", current,
                value -> { cfg().colorHex = value; save(); },
                state.getCurrentScreen()));
    }
}
