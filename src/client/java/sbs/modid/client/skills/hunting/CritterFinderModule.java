/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.hunting;

import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.ModuleSubgroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.core.render.OverlayColor;
import sbs.modid.client.skills.foraging.logic.WaypointPresetDatabase;
import sbs.modid.client.skills.foraging.logic.WaypointPresetPublisher;
import sbs.modid.client.skills.foraging.model.WaypointPresetData;
import sbs.modid.client.skills.foraging.ui.WaypointPresetRows;
import sbs.modid.client.skills.hunting.logic.GemzieWaypointPublisher;
import sbs.modid.client.skills.hunting.model.GemzieSpot;
import sbs.modid.client.ui.settings.SettingRow;
import sbs.modid.client.ui.theme.ThemeColorPickerScreen;

import java.util.ArrayList;
import java.util.List;

/**
 * Critter Finder module (Skills): boxes the critters that hide from you inside the Critter Safari,
 * so one is spotted before it despawns rather than after.
 *
 * <p>Detection and the Safari gate live in
 * {@link sbs.modid.client.skills.hunting.logic.HidingCritterTracker}, the drawing in
 * {@link sbs.modid.client.skills.hunting.render.HidingCritterHighlight}; this class registers the
 * module and its rows. Self-registered via
 * {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 *
 * <p><b>The same sweep also boxes the sparkling critters</b>, in their own colour and with their own
 * toggle, because a sparkling critter is any critter carrying a marker in its nametag rather than a
 * critter with a particular name. It is a section on this page and not a module of its own for the
 * reason {@code HidingCritterTracker} was written for a family: a second module would mean a second
 * entity sweep over the same entities in the same instance. See
 * {@code docs/features/sparkling-critter-highlight.md}.
 *
 * <p><b>The page carries a second feature: Gemzie Waypoints.</b> Markers on the known Gemzie spawn
 * spots, published by {@link sbs.modid.client.skills.hunting.logic.GemzieWaypointPublisher} and drawn
 * by the shared waypoint renderer - there is no drawing code for them anywhere in this package. They
 * live on this page rather than on a card of their own because they are the same trip into the same
 * instance, gated on the same Safari check. See {@code docs/features/gemzie-waypoints.md}.
 *
 * <p><b>And a third half that scans nothing.</b> Snoozles do not roam - they sit behind Cobbled
 * Deepslate and Tuff walls at fixed spots in the Cavern biome, and a wall is not a mob to find but a
 * place to run into. So that half is a marker set, not a scan: five points in the shipped preset
 * file, published and drawn by the same waypoint machinery as the Galatea sets. The rows for it are
 * the shared preset rows; nothing about Snoozles is hard-coded in this package.
 *
 * <p><b>Off by default, and the page says why.</b> The critter this was built for is named
 * {@code Hideonfloor} in the request and appears in no dataset this repository holds - the only
 * critter of that family in the bundled Hypixel data is {@code Hideonleaf}. The name is therefore a
 * setting, the module logs every critter nametag it sees in the Safari, and the rows below tell the
 * player both of those things instead of leaving them to wonder why nothing lights up.
 */
public final class CritterFinderModule implements SbsModule {

    /** Shown as the name field's hint, so an emptied field still says what it wants. */
    private static final String DEFAULT_CRITTERS = "Hideonfloor";

    /** The same, for the sparkling marker field. */
    private static final String DEFAULT_MARKERS = "Sparkling";

    /** The shipped highlight colour, used as the swatch fallback and the picker's starting point. */
    private static final int DEFAULT_COLOR = 0xFFFF2FD5;

    /** The shipped sparkling colour: cyan, unused by anything else the mod draws in the Safari. */
    private static final int DEFAULT_SPARKLING_COLOR = 0xFF4DE8FF;

    /**
     * The preset group holding the Snoozle walls. A constant here rather than a hard-coded list of
     * coordinates: the points, their colour and the player's corrections all live in the preset data
     * and its override file, and this page only needs to know which group to put on screen.
     */
    public static final String SNOOZLE_GROUP = "snoozle-walls-safari";

    /** ServiceLoader needs a public no-arg constructor. */
    public CritterFinderModule() {
    }

    @Override
    public String id() {
        return "critter_finder";
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
        return "Critter Finder";
    }

    @Override
    public String description() {
        return "Boxes the critters that hide from you inside the Critter Safari and the sparkling "
                + "ones, and marks the known Gemzie spawn spots and the walls Snoozles sit behind";
    }

    @Override
    public int accentColor() {
        return 0xFFFF2FD5;
    }

    private static SBSConfig.CritterFinderSettings cfg() {
        return ConfigManager.getInstance().get().critterFinder;
    }

    private static SBSConfig.SparklingSettings sparkling() {
        return cfg().sparkling;
    }

    private static SBSConfig.GemzieSettings gemzie() {
        return cfg().gemzie;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    /** Saves, then rebuilds the marker set so a changed colour or fade is on screen immediately. */
    private static void saveGemzie() {
        save();
        GemzieWaypointPublisher.getInstance().refresh();
    }

    @Override
    public List<SettingRow> settings() {
        List<SettingRow> rows = new ArrayList<>(critterRows());
        rows.addAll(sparklingRows());
        rows.addAll(gemzieRows());
        rows.addAll(snoozleRows());
        return rows;
    }

    /** The critter-boxing half of the page: the scan, its colour, its range and its ping. */
    private static List<SettingRow> critterRows() {
        return List.of(
                SettingRow.toggle("Critter Finder", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("Boxes the critters that hide from you inside the Critter Safari, "
                                + "with their name above the box and an optional line to them. Only "
                                + "ever runs inside the Safari - everywhere else it costs nothing at "
                                + "all. Off by default; see the note at the bottom of this page."),
                SettingRow.label("§8Nothing is scanned or drawn anywhere but the Critter Safari"),

                SettingRow.text("Critters To Find", DEFAULT_CRITTERS, 64,
                        () -> cfg().critterNames == null ? "" : cfg().critterNames,
                        value -> { cfg().critterNames = value == null ? "" : value.trim(); save(); })
                        .describe("Which critters to box, separated by commas. Matched without "
                                + "case and on whole words, so the level, the type symbol and the "
                                + "health in the nametag are all ignored. Default: Hideonfloor. Put "
                                + "more than one name here to box more than one critter."),
                SettingRow.label("§8If nothing lights up, the name is wrong - the log lists the real ones"),

                SettingRow.color("Highlight Colour", () -> cfg().colorHex,
                        () -> DEFAULT_COLOR,
                        CritterFinderModule::openColorPicker)
                        .describe("The colour of the box, the name and the pointer line. Magenta "
                                + "by default because nothing else the mod draws uses it, so a "
                                + "critter is never mistaken for a trap, a waypoint or a quest "
                                + "animal."),
                SettingRow.toggle("Pointer Line To The Critter", () -> cfg().showTracer,
                        () -> { cfg().showTracer = !cfg().showTracer; save(); })
                        .anchor("tracer_to_the_critter")
                        .describe("A line from your crosshair to the critter, so you know which way "
                                + "to walk before the box is even on screen. Applies to sparkling "
                                + "critters too. On by default."),
                SettingRow.label("§8Only critters you have a clear line to are boxed"),
                SettingRow.rangeSlider("Maximum Distance", 8, 128, () -> cfg().renderDistance,
                        value -> { cfg().renderDistance = value; save(); }, "m")
                        .describe("How far away a critter is still looked for and drawn. This caps "
                                + "the search as well as the drawing, so a lower number is less work "
                                + "per scan. Covers both halves of the search - watched critters and "
                                + "sparkling ones. Default: 64."),
                SettingRow.toggle("Ping When Found", () -> cfg().foundPing,
                        () -> { cfg().foundPing = !cfg().foundPing; save(); })
                        .describe("One chat line and a short sound the first time each critter is "
                                + "seen, and then silence for that critter. Sparkling critters have "
                                + "their own ping below. On by default."),

                SettingRow.label("§8—— Accuracy ——"),
                SettingRow.label("§eThe critter name has not been confirmed in game yet."),
                SettingRow.label("§8That is why this is off by default. Every critter nametag seen in the"),
                SettingRow.label("§8Safari is written to the log as [SBS][Critter] - if the boxes never"),
                SettingRow.label("§8appear, that line says what the critters here are really called, and"),
                SettingRow.label("§8putting the right name in the field above fixes it with no update."));
    }

    /**
     * The sparkling half of the page: the same sweep, a second reason to box something.
     *
     * <p><b>Its own toggle, and only four rows.</b> Distance, tracer and occlusion are the rows
     * above - they describe how a critter is <i>drawn</i>, which does not change with the reason it
     * was found - so duplicating them here would give the player two answers to one question. What
     * is duplicated is what genuinely differs: the colour, and the ping.
     */
    private static List<SettingRow> sparklingRows() {
        List<SettingRow> rows = new ArrayList<>();
        rows.add(SettingRow.label("§8—— Sparkling Critters ——"));

        rows.add(SettingRow.toggle("Sparkling Critters", () -> sparkling().enabled,
                        () -> { sparkling().enabled = !sparkling().enabled; save(); })
                .anchor("sparkling_enabled")
                .describe("Boxes any critter in the Critter Safari whose nametag carries the "
                        + "sparkling marker, whatever the critter is called, in its own colour. "
                        + "Works on its own - the Critter Finder above does not have to be on. Off "
                        + "by default; see the note under these rows."));
        rows.add(SettingRow.label("§8Independent of the watch list above - any critter can sparkle"));

        rows.add(SettingRow.text("Sparkling Marker", DEFAULT_MARKERS, 32,
                        () -> sparkling().markers == null ? "" : sparkling().markers,
                        value -> {
                            sparkling().markers = value == null ? "" : value.trim();
                            save();
                        })
                .anchor("sparkling_marker")
                .describe("What marks a critter as sparkling, separated by commas. Matched without "
                        + "case against the whole nametag rather than the critter's name, so a "
                        + "symbol works here as well as a word. Default: Sparkling."));

        rows.add(SettingRow.color("Sparkling Colour", () -> sparkling().colorHex,
                        () -> DEFAULT_SPARKLING_COLOR,
                        CritterFinderModule::openSparklingPicker)
                .anchor("sparkling_color")
                .describe("The colour of the box, the label and the pointer line for a sparkling "
                        + "critter. Cyan by default, so it is never mistaken for the magenta "
                        + "critter box, a Gemzie spot or a waypoint. The label also carries a ✦, "
                        + "so the two kinds stay apart whatever colours you pick."));

        rows.add(SettingRow.toggle("Ping For Sparkling", () -> sparkling().ping,
                        () -> { sparkling().ping = !sparkling().ping; save(); })
                .anchor("sparkling_ping")
                .describe("One chat line and a higher-pitched sound the first time each sparkling "
                        + "critter is seen. Separate from the ping above, so the common critters can "
                        + "stay silent while these do not. On by default."));

        rows.add(SettingRow.label("§8—— Sparkling Accuracy ——"));
        rows.add(SettingRow.label("§eIt is not confirmed how Hypixel marks a sparkling critter."));
        rows.add(SettingRow.label("§8No critter in the data this build holds carries the word, which is"));
        rows.add(SettingRow.label("§8why it is off by default. It may also be a symbol rather"));
        rows.add(SettingRow.label("§8than a word - the field above takes either. The [SBS][Critter] log"));
        rows.add(SettingRow.label("§8line lists the names nearby and the markers being watched."));
        return rows;
    }

    /**
     * The Gemzie Waypoints half of the page.
     *
     * <p><b>Every per-spot row is generated from {@link GemzieSpot}.</b> There is no code for spot
     * one, two or three: the loop below emits a colour and an opacity row for each constant the enum
     * declares, so a fourth spawn spot is one line in that enum and its controls appear on their own.
     * Hard-coding even one spot's row would quietly destroy that.
     */
    private static List<SettingRow> gemzieRows() {
        List<SettingRow> rows = new ArrayList<>();
        rows.add(SettingRow.label("§8—— Gemzie Waypoints ——"));

        rows.add(SettingRow.toggle("Gemzie Waypoints", () -> gemzie().enabled,
                        () -> { gemzie().enabled = !gemzie().enabled; saveGemzie(); })
                .anchor("gemzie_enabled")
                .describe("Marks the known Gemzie spawn spots with the mod's usual waypoint - a "
                        + "box, a beam and a label - drawn through walls so a spot is found before "
                        + "it is in sight. Uses the same Safari check as the critter highlight "
                        + "above, so nothing exists outside the Safari. Off by default; see the "
                        + "note at the bottom of this page."));
        rows.add(SettingRow.label("§8Nothing is published or drawn anywhere but the Critter Safari"));

        rows.add(SettingRow.toggle("Distance On Labels", () -> gemzie().showDistance,
                        () -> { gemzie().showDistance = !gemzie().showDistance; saveGemzie(); })
                .anchor("gemzie_distance")
                .describe("Adds how far away a spot is to its label (\"Gemzie 1 12m\"). On by "
                        + "default - inside one small instance the distance is what tells two "
                        + "markers apart at a glance."));

        rows.add(SettingRow.rangeSlider("Fade Out Within", 0, 16, () -> gemzie().fadeWithin,
                        value -> { gemzie().fadeWithin = value; saveGemzie(); }, "m")
                .anchor("gemzie_fade")
                .describe("A marker fades out over the last few blocks and is gone by the time you "
                        + "are standing on the spot, so the beam and the label are not in front of "
                        + "the critter you came to interact with. 0 keeps them at full strength all "
                        + "the way in. Default: 6."));

        for (GemzieSpot spot : GemzieSpot.values()) {
            rows.addAll(spotRows(spot));
        }

        rows.add(SettingRow.button("Reset Gemzie Colours", () -> {
            gemzie().resetSpots();
            saveGemzie();
        }).anchor("gemzie_reset")
                .describe("Forgets every colour and opacity you set here - the shipped ones come "
                        + "back, because they were never overwritten in the first place."));

        rows.add(SettingRow.label("§8Now: " + GemzieWaypointPublisher.getInstance().status()));
        rows.add(SettingRow.label("§8" + gemzie().changedSpots() + " spot(s) changed by you"));

        rows.add(SettingRow.label("§8—— Gemzie Accuracy ——"));
        rows.add(SettingRow.label("§eThese three positions have not been confirmed in game yet."));
        rows.add(SettingRow.label("§8They came with the request and appear in no data this build"));
        rows.add(SettingRow.label("§8holds, which is why this is off by default. If a marker stands"));
        rows.add(SettingRow.label("§8in the wrong place it is the coordinates that are wrong, not"));
        rows.add(SettingRow.label("§8the feature."));
        return rows;
    }

    /** One spot's block: its colour and how strongly it is drawn. */
    private static List<SettingRow> spotRows(GemzieSpot spot) {
        return List.of(
                SettingRow.color(spot.label() + " Colour",
                                () -> gemzie().colorHexOf(spot),
                                () -> 0xFF000000 | spot.defaultRgb(),
                                () -> openSpotPicker(spot))
                        .anchor(spot.id() + "_color")
                        .describe("The colour of this spot's box, beam and label."),
                SettingRow.rangeSlider(spot.label() + " Opacity", 0, 100,
                                () -> gemzie().opacityOf(spot),
                                value -> {
                                    gemzie().opacity.put(spot.id(), value);
                                    saveGemzie();
                                }, "%")
                        .anchor(spot.id() + "_opacity")
                        .describe("How strongly this marker is drawn, as a share of the strength "
                                + "the others get. Below 100 the whole marker - box, beam and "
                                + "label - is proportionally fainter, which is how a spot is made "
                                + "to sit behind the other two without changing its colour."));
    }

    /** Opens the shared colour picker on a spot's current colour. */
    private static void openSpotPicker(GemzieSpot spot) {
        GuiStateManager state = GuiStateManager.getInstance();
        net.minecraft.client.Minecraft.getInstance().setScreenAndShow(new ThemeColorPickerScreen(
                "Gemzie  •  " + spot.label(), gemzie().colorHexOf(spot),
                value -> {
                    gemzie().colorHex.put(spot.id(), value);
                    saveGemzie();
                },
                state.getCurrentScreen()));
    }

    /**
     * The Snoozle half of the page: waypoints on the walls Snoozles sit behind.
     *
     * <p><b>Not a second renderer and not a second config block.</b> The five spots are a group in
     * the shipped preset file, so they are published, drawn, coloured and corrected by the same
     * machinery as the Galatea sets - box, beam, name and distance included - and these rows are the
     * shared ones. The group declares this page as its owner, which is why it appears here and not
     * on the Galatea Waypoints page.
     *
     * <p>A missing group is a normal degraded state (an unreadable data file), so the page says so
     * rather than silently dropping three rows.
     */
    private static List<SettingRow> snoozleRows() {
        List<SettingRow> rows = new ArrayList<>();
        rows.add(SettingRow.label("§8—— Snoozle Walls  •  Critter Safari, Cavern ——"));

        WaypointPresetData.Group group = WaypointPresetDatabase.group(SNOOZLE_GROUP);
        if (group == null) {
            rows.add(SettingRow.label("§eThe Snoozle spots are not in the loaded preset data."));
            rows.add(SettingRow.label("§8" + WaypointPresetDatabase.status()));
            return rows;
        }

        rows.addAll(WaypointPresetRows.group(group, null, "Snoozle Walls",
                "Marks the " + group.points.size() + " walls Snoozles sit behind in the Cavern "
                        + "biome, with a beam and the distance. A Snoozle never stands in the open - "
                        + "the marker is a Cobbled Deepslate and Tuff wall you run into three times "
                        + "to break through, not a mob you can see. Only ever drawn inside the "
                        + "Safari's Cavern; anywhere else it costs nothing."));

        rows.add(SettingRow.label("§8Run into the marked wall three times to break through to it"));
        rows.add(SettingRow.label("§8Now: " + WaypointPresetPublisher.getInstance().status()));
        rows.add(SettingRow.label("§8You are: " + SkyBlockLocation.describe()));
        rows.add(SettingRow.label("§eThese five coordinates have not been confirmed in game yet."));
        rows.add(SettingRow.label("§8Off by default for that reason. While the markers are on and you"));
        rows.add(SettingRow.label("§8are near one, the block really at that spot is written to the log"));
        rows.add(SettingRow.label("§8as [SBS][Snoozle] - one trip settles whether the list is right."));
        return rows;
    }

    /** Opens the shared colour picker on the current colour, saving whatever comes back. */
    private static void openColorPicker() {
        String current = cfg().colorHex == null || cfg().colorHex.isBlank()
                ? OverlayColor.toHex(DEFAULT_COLOR)
                : cfg().colorHex;
        GuiStateManager state = GuiStateManager.getInstance();
        net.minecraft.client.Minecraft.getInstance().setScreenAndShow(new ThemeColorPickerScreen(
                "Critter Finder  •  Highlight", current,
                value -> { cfg().colorHex = value; save(); },
                state.getCurrentScreen()));
    }

    /** The same picker for the sparkling colour, so the two are set the one way colours are set. */
    private static void openSparklingPicker() {
        String current = sparkling().colorHex == null || sparkling().colorHex.isBlank()
                ? OverlayColor.toHex(DEFAULT_SPARKLING_COLOR)
                : sparkling().colorHex;
        GuiStateManager state = GuiStateManager.getInstance();
        net.minecraft.client.Minecraft.getInstance().setScreenAndShow(new ThemeColorPickerScreen(
                "Critter Finder  •  Sparkling", current,
                value -> { sparkling().colorHex = value; save(); },
                state.getCurrentScreen()));
    }
}
