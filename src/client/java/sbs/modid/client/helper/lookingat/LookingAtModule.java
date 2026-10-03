/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.lookingat;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.ui.HudEditorScreen;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.List;

/**
 * Looking At module (Quality of Life): a crosshair chip naming the block or mob under the
 * crosshair, far beyond the distance a nametag renders at. Self-registered via
 * {@code META-INF/services/sbs.modid.client.core.module.SbsModule}; the rendering (and the
 * throttled ray scan) lives in {@link LookingAtHud}.
 */
public final class LookingAtModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public LookingAtModule() {
    }

    @Override
    public String id() {
        return "looking_at";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.QUALITY_OF_LIFE;
    }

    @Override
    public String displayName() {
        return "Looking At";
    }

    @Override
    public String description() {
        return "Names the block or mob under your crosshair, far beyond nametag range";
    }

    private static SBSConfig.LookingAtSettings cfg() {
        return ConfigManager.getInstance().get().lookingAt;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Looking At", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("A small card under the crosshair naming whatever you aim at - "
                                + "the block type, or the mob with its full SkyBlock nametag "
                                + "(level, name, health) - long before you are close enough to "
                                + "read the tag in the world. A mob behind a wall is never named: "
                                + "whatever the crosshair ray hits first wins."),
                SettingRow.label("§8Nothing is drawn while nothing is under the crosshair"),

                SettingRow.toggle("Name Blocks", () -> cfg().showBlocks,
                        () -> { cfg().showBlocks = !cfg().showBlocks; save(); })
                        .describe("Show the block type when the crosshair rests on a block. "
                                + "Torches, crops and other thin decoration count as targets, "
                                + "exactly like the vanilla crosshair picks them."),
                SettingRow.toggle("Name Mobs & Players", () -> cfg().showEntities,
                        () -> { cfg().showEntities = !cfg().showEntities; save(); })
                        .describe("Show mobs and players. A SkyBlock mob shows Hypixel's own "
                                + "coloured nametag line, read off the tag that floats over it - "
                                + "the name data is synced far beyond the distance the tag "
                                + "renders at. Players show their name, everything else its "
                                + "type."),
                SettingRow.intField("Range", 5, 200, () -> cfg().range,
                        value -> { cfg().range = value; save(); }, "m")
                        .describe("How far the crosshair ray reaches. Mobs the server no longer "
                                + "sends at that distance cannot be named no matter the setting - "
                                + "blocks go as far as your render distance."),

                SettingRow.toggle("Show Distance", () -> cfg().showDistance,
                        () -> { cfg().showDistance = !cfg().showDistance; save(); })
                        .describe("A second line with the distance to the target, live while you "
                                + "approach."),
                SettingRow.toggle("Show Block Position", () -> cfg().showCoords,
                        () -> { cfg().showCoords = !cfg().showCoords; save(); })
                        .describe("Adds the block's coordinates to the second line - handy for "
                                + "calling out a spot."),
                SettingRow.toggle("SkyBlock Block Names", () -> cfg().skyblockBlockNames,
                        () -> { cfg().skyblockBlockNames = !cfg().skyblockBlockNames; save(); })
                        .describe("Names blocks the way SkyBlock does rather than the way "
                                + "Minecraft does: Mithril instead of Gray Wool, Titanium instead "
                                + "of Diamond Ore, Ruby instead of Red Stained Glass. Hypixel "
                                + "builds its ores out of ordinary blocks with nothing on them to "
                                + "say so, so this is decided by the island you are on - wool on "
                                + "your own island stays wool. The vanilla name moves to the "
                                + "second line instead of being lost."),
                SettingRow.label("§8Known on the Dwarven Mines, Crystal Hollows and Mineshafts"),

                SettingRow.button("Edit GUI Position", () -> open(new HudEditorScreen(
                        new HudElement[] {HudElement.LOOKING_AT}, "Edit Looking At")))
                        .describe("Opens the editor where you drag the card anywhere on the "
                                + "screen and scale it."));
    }

    private static void open(net.minecraft.client.gui.screens.Screen screen) {
        net.minecraft.client.Minecraft.getInstance().setScreenAndShow(screen);
    }
}
