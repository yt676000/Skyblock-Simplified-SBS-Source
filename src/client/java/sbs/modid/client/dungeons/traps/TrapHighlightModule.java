/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.traps;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.core.render.OverlayColor;
import sbs.modid.client.dungeons.traps.logic.TrapIndex;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.List;

/**
 * Trap Highlighter module (Dungeons): boxes the Catacombs' tripwire and arrow dispensers.
 *
 * <p>Self-registered via {@code META-INF/services/sbs.modid.client.core.module.SbsModule}; every
 * option writes to {@link SBSConfig.TrapHighlightSettings} and is read live by {@link TrapIndex} and
 * its renderer, so changes apply without a restart.
 */
public final class TrapHighlightModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public TrapHighlightModule() {
    }

    @Override
    public String id() {
        return "trap_highlight";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.DUNGEONS;
    }

    @Override
    public String displayName() {
        return "Trap Highlighter";
    }

    @Override
    public String description() {
        return "Boxes tripwire and arrow dispensers inside a dungeon run, before you walk into them";
    }

    @Override
    public int accentColor() {
        return 0xFFFF6B4A;
    }

    private static SBSConfig.TrapHighlightSettings cfg() {
        return ConfigManager.getInstance().get().trapHighlight;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    /**
     * The palette as a dropdown rather than a click-through cycler: nine colours is well past the two
     * a cycling control may carry, and the build refuses that anyway ({@code ui/AGENTS.md}).
     */
    private static List<String> colourNames() {
        List<String> names = new java.util.ArrayList<>(OverlayColor.values().length);
        for (OverlayColor colour : OverlayColor.values()) {
            names.add(colour.displayName());
        }
        return names;
    }

    /** The colour a dropdown label names; an unknown label keeps the palette's first entry. */
    private static OverlayColor colourOf(String displayName) {
        for (OverlayColor colour : OverlayColor.values()) {
            if (colour.displayName().equals(displayName)) {
                return colour;
            }
        }
        return OverlayColor.RED;
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Trap Highlighter", () -> cfg().enabled,
                                () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("Marks the Catacombs' traps in the world: tripwire, which is very "
                                + "nearly invisible in game, and the arrow dispensers that sit in "
                                + "walls and ceilings where nobody looks."),
                SettingRow.label("§8Only inside a run - never in the hub or at the dungeon entrance"),
                SettingRow.label("§8By default only traps you have a clear line to are drawn"),

                SettingRow.toggle("Tripwire", () -> cfg().tripwires,
                                () -> { cfg().tripwires = !cfg().tripwires; save(); })
                        .describe("A whole trip line is drawn as one shape from end to end rather "
                                + "than as a dozen separate cubes - where the line is and how far "
                                + "it reaches is the thing you actually need to see."),
                SettingRow.options("Tripwire Colour", TrapHighlightModule::colourNames,
                                () -> cfg().tripwireColor.displayName(),
                                picked -> { cfg().tripwireColor = colourOf(picked); save(); })
                        .describe("Colour of the trip lines."),

                SettingRow.toggle("Dispensers", () -> cfg().dispensers,
                                () -> { cfg().dispensers = !cfg().dispensers; save(); })
                        .describe("Boxes every arrow dispenser, including the ones sunk into a wall "
                                + "or a ceiling where they cannot normally be seen at all."),
                SettingRow.options("Dispenser Colour", TrapHighlightModule::colourNames,
                                () -> cfg().dispenserColor.displayName(),
                                picked -> { cfg().dispenserColor = colourOf(picked); save(); })
                        .describe("Colour of the dispenser boxes. Worth keeping different from the "
                                + "tripwire colour so the two read apart at a glance."),
                SettingRow.toggle("Show Which Way It Fires", () -> cfg().showFacing,
                                () -> { cfg().showFacing = !cfg().showFacing; save(); })
                        .describe("Adds a short spike out of the dispenser's face pointing the way "
                                + "its arrows travel, so you know which wall to hug. Read from the "
                                + "block itself, not guessed from its surroundings."),

                SettingRow.toggle("Show Through Walls", () -> cfg().showThroughWalls,
                                () -> { cfg().showThroughWalls = !cfg().showThroughWalls; save(); })
                        .describe("Off, only traps you actually have a clear line to are drawn - a "
                                + "trap two rooms away is not the one about to go off under you. On, "
                                + "every indexed trap in range shows regardless of what is in front "
                                + "of it, which is worth having while you are learning a room."),
                SettingRow.label("§8A dispenser sunk into a wall still counts as visible: you can"),
                SettingRow.label("§8see its face, which is the side the arrows come out of"),

                SettingRow.rangeSlider("Draw Distance", 8, 128, () -> cfg().renderDistance,
                                value -> { cfg().renderDistance = value; save(); }, "m")
                        .describe("How far away a trap is still drawn, in blocks. Lower keeps a big "
                                + "room readable; higher shows you the far side of a corridor."),
                SettingRow.label("§8Traps in chunks that have not loaded cannot be marked, and are"),
                SettingRow.label("§8never remembered from a previous run of the same room"));
    }
}
