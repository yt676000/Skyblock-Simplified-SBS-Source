/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.floordrop;

import net.minecraft.client.Minecraft;
import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.core.render.OverlayColor;
import sbs.modid.client.helper.floordrop.logic.FloorDropAreas;
import sbs.modid.client.helper.floordrop.logic.FloorDropTracker;
import sbs.modid.client.ui.settings.SettingRow;
import sbs.modid.client.ui.theme.ThemeColorPickerScreen;

import java.util.ArrayList;
import java.util.List;

/**
 * Floor Drops module (Skills): boxes the loot lying on the ground in Galatea, Torrhus Canyon and the
 * Critter Safari, so a drop is seen before it is walked past.
 *
 * <p>Detection and the area gate live in
 * {@link sbs.modid.client.helper.floordrop.logic.FloorDropTracker} and
 * {@link FloorDropAreas}, the particle corroboration in
 * {@link sbs.modid.client.helper.floordrop.logic.FloorDropParticles} and the drawing in
 * {@link sbs.modid.client.helper.floordrop.render.FloorDropHighlight}; this class registers the
 * module and its rows. Self-registered via
 * {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 *
 * <p><b>In {@code helper} rather than under a skill, and the group is still Skills.</b> The three
 * areas span two themes - Galatea and Torrhus Canyon are the foraging islands, while the Critter
 * Safari is hunting content with its own gate in {@code skills/hunting} - so a feature covering all
 * three belongs to neither package. {@code helper} is what the root {@code AGENTS.md} keeps for
 * exactly that, and {@code helper/experiment} and {@code helper/yearofthepig} are the precedent for
 * a {@code helper} module that shows up under Skills.
 *
 * <p><b>Off by default, and the page says why.</b> Three facts underneath this feature were named
 * rather than measured: that a floor drop arrives as an item display entity, that the item it is
 * built on is String, and that the particles around it are the ones configured here. All three are
 * logged as {@code [SBS][FloorDrop]} every few seconds while the feature is on, and the page prints
 * both what the last sweep walked past and the items it was made of - so one trip into Galatea
 * settles them without an update. See {@code docs/features/floor-drop-highlight.md}.
 *
 * <p><b>There is no draw-through-walls row, and its absence is deliberate.</b> A drop with terrain
 * in the way is not drawn at all. The page carries a label saying so, because a player who has set
 * that row on the mod's other highlighters will look for it here and a missing row reads as a bug
 * until something says otherwise.
 */
public final class FloorDropModule implements SbsModule {

    /** The shipped highlight colour, used as the swatch fallback and the picker's starting point. */
    private static final int DEFAULT_COLOR = 0xFFFFFFFF;

    /** Shown as the particle field's hint, so an emptied field still says what it wants. */
    private static final String DEFAULT_PARTICLES = "happy_villager, composter";

    /** Shown as the item-kind field's hint, and named in its own description. */
    private static final String DEFAULT_ITEM_TYPES = "string";

    /** ServiceLoader needs a public no-arg constructor. */
    public FloorDropModule() {
    }

    @Override
    public String id() {
        return "floor_drops";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.SKILLS;
    }

    @Override
    public String displayName() {
        return "Floor Drops";
    }

    @Override
    public String description() {
        return "Boxes the loot lying on the ground in Galatea, Torrhus Canyon and the Critter "
                + "Safari, with a pointer line and the distance to it";
    }

    @Override
    public int accentColor() {
        return 0xFF8FE388;
    }

    private static SBSConfig.FloorDropSettings cfg() {
        return ConfigManager.getInstance().get().floorDrops;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        List<SettingRow> rows = new ArrayList<>(drawingRows());
        rows.addAll(detectionRows());
        rows.addAll(accuracyRows());
        return rows;
    }

    /** The half the player came for: whether it runs, and how a drop is drawn. */
    private static List<SettingRow> drawingRows() {
        return List.of(
                SettingRow.toggle("Floor Drops", () -> cfg().enabled,
                                () -> { cfg().enabled = !cfg().enabled; save(); })
                        .anchor("floor_drops_enabled")
                        .describe("Boxes the loot lying on the ground - the drops you have to walk "
                                + "over to collect - with what it is above the box. Only ever runs "
                                + "in " + FloorDropAreas.describe() + "; everywhere else it costs "
                                + "nothing at all. Off by default; see the note at the bottom of "
                                + "this page."),
                SettingRow.label("§8Nothing is scanned or drawn outside " + FloorDropAreas.describe()),

                SettingRow.color("Highlight Colour", () -> cfg().colorHex,
                                () -> DEFAULT_COLOR,
                                FloorDropModule::openColorPicker)
                        .anchor("floor_drops_color")
                        .describe("The colour of the box, the label and the pointer line. White by "
                                + "default: every other highlight in these areas is already a "
                                + "colour - the critter box magenta, sparkling cyan, the Gemzie "
                                + "spots green, purple and orange - and the drop itself glows "
                                + "green, so a green box would be lost in its own particles."),

                SettingRow.toggle("Pointer Line To The Drop", () -> cfg().showTracer,
                                () -> { cfg().showTracer = !cfg().showTracer; save(); })
                        .anchor("floor_drops_tracer")
                        .describe("A line from your crosshair to the drop, so you know which way to "
                                + "walk before the box is even on screen. On by default."),

                SettingRow.toggle("Distance On Labels", () -> cfg().showDistance,
                                () -> { cfg().showDistance = !cfg().showDistance; save(); })
                        .anchor("floor_drops_distance")
                        .describe("Adds how far away a drop is to its label (\"Enchanted Bone "
                                + "§712m§r\"), measured from you rather than from the camera. On by "
                                + "default."),

                SettingRow.label("§8A drop behind terrain is not drawn - there is no row for it"),

                SettingRow.rangeSlider("Maximum Distance", 8, 128, () -> cfg().renderDistance,
                                value -> { cfg().renderDistance = value; save(); }, "m")
                        .anchor("floor_drops_range")
                        .describe("How far away a drop is still looked for and drawn. This caps the "
                                + "search as well as the drawing, so a lower number is less work per "
                                + "scan. Default: 48."));
    }

    /**
     * The half that decides what counts as a drop.
     *
     * <p>Every row here exists because something under this feature is unverified, and each one is
     * the fix for its own guess without an update - which is why they are settings rather than
     * constants.
     */
    private static List<SettingRow> detectionRows() {
        List<SettingRow> rows = new ArrayList<>();
        rows.add(SettingRow.label("§8—— What Counts As A Drop ——"));

        rows.add(SettingRow.text("Drop Item Types", DEFAULT_ITEM_TYPES, 96,
                        () -> cfg().itemTypes == null ? "" : cfg().itemTypes,
                        value -> {
                            cfg().itemTypes = value == null ? "" : value.trim();
                            save();
                        })
                .anchor("floor_drops_item_types")
                .describe("Which item a drop has to be, separated by commas, written the way the "
                        + "game names it. The loot lying on the ground here is the String item "
                        + "wearing a custom model and the scenery around it is not, so this is what "
                        + "keeps the boxes on loot. A bare name is enough - \"string\" works. "
                        + "Empty boxes a drop whatever it is made of. Default: "
                        + DEFAULT_ITEM_TYPES + ". The Accuracy rows below list what is actually "
                        + "standing around you, which is how this field gets a different value if "
                        + "some drop turns out to be built on something else."));

        rows.add(SettingRow.rangeSlider("Ground Within", 0, 8, () -> cfg().groundWithin,
                        value -> { cfg().groundWithin = value; save(); }, "m")
                .anchor("floor_drops_ground")
                .describe("How far above solid ground a drop may sit and still be boxed. This is "
                        + "what separates loot from scenery: the game builds decoration out of the "
                        + "same kind of entity, and decoration is mounted on walls and floated "
                        + "inside builds while loot lies on the floor. 0 switches the test off, "
                        + "which is the answer if real drops hover. Default: 2."));

        rows.add(SettingRow.toggle("Also Box Dropped Items", () -> cfg().includeDroppedItems,
                        () -> {
                            cfg().includeDroppedItems = !cfg().includeDroppedItems;
                            save();
                        })
                .anchor("floor_drops_dropped_items")
                .describe("Box ordinary dropped items as well as the display entities the sweep is "
                        + "written for. Off by default, because it would otherwise box everything "
                        + "any player nearby has thrown on the floor. Switch it on if the row below "
                        + "says there are dropped items around and no displays - that means the "
                        + "drops here are not the kind this was built for."));

        rows.add(SettingRow.toggle("Require Particles", () -> cfg().requireParticles,
                        () -> { cfg().requireParticles = !cfg().requireParticles; save(); })
                .anchor("floor_drops_require_particles")
                .describe("Only box a drop that has had one of the particles below beside it in the "
                        + "last few seconds. Off by default: the game does not tell the client which "
                        + "entity a particle belongs to, so this is a proximity guess, and the "
                        + "particle itself has not been confirmed. Switch it on if decoration is "
                        + "being boxed."));

        rows.add(SettingRow.text("Particle Types", DEFAULT_PARTICLES, 96,
                        () -> cfg().particleTypes == null ? "" : cfg().particleTypes,
                        value -> {
                            cfg().particleTypes = value == null ? "" : value.trim();
                            save();
                        })
                .anchor("floor_drops_particle_types")
                .describe("Which particles count as a drop's, separated by commas, written the way "
                        + "the game names them. A bare name is enough - \"happy_villager\" works. "
                        + "Default: " + DEFAULT_PARTICLES + ". The log line lists the ones actually "
                        + "arriving around you, which is how this field gets the right value."));

        rows.add(SettingRow.text("Only These Drops", "any drop", 96,
                        () -> cfg().itemFilter == null ? "" : cfg().itemFilter,
                        value -> {
                            cfg().itemFilter = value == null ? "" : value.trim();
                            save();
                        })
                .anchor("floor_drops_filter")
                .describe("Box only drops whose name or item id contains one of these, separated by "
                        + "commas. Empty - the default - boxes every drop. This is how you stop "
                        + "seeing the common ones, and the way out if the scenery in an area sits "
                        + "on the floor too."));
        return rows;
    }

    /**
     * What the feature currently sees, and what about it is not yet confirmed.
     *
     * <p>The live counts are the point of this block. "0 item displays, 14 dropped items in range"
     * answers the one question this feature cannot answer from inside the client, on the page,
     * without anybody opening a log file - which is the failure recorded in {@code docs/issues} as a
     * feature whose only diagnostic was invisible.
     */
    private static List<SettingRow> accuracyRows() {
        List<SettingRow> rows = new ArrayList<>();
        rows.add(SettingRow.label("§8—— Accuracy ——"));
        rows.add(SettingRow.label("§8Now: " + FloorDropTracker.getInstance().status()));
        rows.add(SettingRow.label("§8Items around you: " + FloorDropTracker.getInstance().typesLine()));
        rows.add(SettingRow.label("§8You are: " + SkyBlockLocation.describe()));
        rows.add(SettingRow.label("§eNo floor drop has been captured in game yet."));
        rows.add(SettingRow.label("§8Three things were named rather than measured: that a drop is"));
        rows.add(SettingRow.label("§8an \"item display\" entity, that it is the String item, and"));
        rows.add(SettingRow.label("§8that its particles are the ones set above. That is why this is"));
        rows.add(SettingRow.label("§8off by default. All three are written to the log as"));
        rows.add(SettingRow.label("§8[SBS][FloorDrop], and the row above lists the items in range,"));
        rows.add(SettingRow.label("§8so one trip fixes any of them from the rows above."));
        return rows;
    }

    /** Opens the shared colour picker on the current colour, saving whatever comes back. */
    private static void openColorPicker() {
        String current = cfg().colorHex == null || cfg().colorHex.isBlank()
                ? OverlayColor.toHex(DEFAULT_COLOR)
                : cfg().colorHex;
        GuiStateManager state = GuiStateManager.getInstance();
        Minecraft.getInstance().setScreenAndShow(new ThemeColorPickerScreen(
                "Floor Drops  •  Highlight", current,
                value -> { cfg().colorHex = value; save(); },
                state.getCurrentScreen()));
    }
}
