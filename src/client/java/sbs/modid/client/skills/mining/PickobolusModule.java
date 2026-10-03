/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.ModuleSubgroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.List;

/**
 * Pickobolus Preview module (Skills): highlights the blocks the pickaxe-throw ability would break,
 * where you are aiming, so a throw can be lined up on a full vein before the cooldown is spent.
 * Self-registered via {@code META-INF/services/sbs.modid.client.core.module.SbsModule}; state in
 * {@link SBSConfig.PickobolusSettings}, drawing in
 * {@link sbs.modid.client.skills.mining.render.PickobolusHighlight}.
 */
public final class PickobolusModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public PickobolusModule() {
    }

    @Override
    public String id() {
        return "pickobolus_preview";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.SKILLS;
    }

    @Override
    public ModuleSubgroup subgroup() {
        return ModuleSubgroup.MINING;
    }

    @Override
    public String displayName() {
        return "Pickobolus Preview";
    }

    @Override
    public String description() {
        return "Highlights the blocks a Pickobolus throw would break, where you are aiming";
    }

    @Override
    public int accentColor() {
        return 0xFF30E030;
    }

    private static SBSConfig.PickobolusSettings cfg() {
        return ConfigManager.getInstance().get().pickobolus;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Only On Mining Islands", MiningRoutesModule::islandLock,
                        MiningRoutesModule::toggleIslandLock)
                        .describe("Keeps every mining feature quiet unless you are on a mining "
                                + "island. One shared switch - changing it here changes it for "
                                + "all mining modules."),
                SettingRow.label("Shared by every mining feature; off anywhere else"),
                SettingRow.label("§8" + sbs.modid.client.skills.SkillIslands.describe(
                        sbs.modid.client.skills.SkillIslands.MINING_ISLANDS)),

                SettingRow.toggle("Pickobolus Preview", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("While you hold a pickaxe with the Pickobolus ability, shows the "
                                + "blocks the throw would break at the spot you are aiming at - so "
                                + "you can centre it on a vein instead of spending the cooldown to "
                                + "find out."),
                SettingRow.label("Shows the throw's blast area where you aim, before you use it"),

                SettingRow.enumOptions("Highlight Colour", () -> cfg().color,
                        value -> { cfg().color = value; save(); }, v -> v.displayName())
                        .describe("The colour of the highlighted blocks. Click to cycle."),
                SettingRow.toggle("Shade Blocks", () -> cfg().fillBlocks,
                        () -> { cfg().fillBlocks = !cfg().fillBlocks; save(); })
                        .describe("Fills the affected blocks in as well as outlining them. Turn off "
                                + "for just the outlines if the shading is too busy underground."),
                SettingRow.toggle("Outline Blast Area", () -> cfg().showAreaOutline,
                        () -> { cfg().showAreaOutline = !cfg().showAreaOutline; save(); })
                        .describe("Draws one box around the whole affected volume, so the reach of "
                                + "the throw is readable even when the blocks inside are hidden "
                                + "behind the face you are looking at."),
                SettingRow.toggle("Ores Only", () -> cfg().oresOnly,
                        () -> { cfg().oresOnly = !cfg().oresOnly; save(); })
                        .describe("Narrows the highlight to blocks that look like ores. Hypixel's "
                                + "ores are ordinary blocks wearing a texture pack, so this guess "
                                + "can miss - leave it off to see the whole blast area."),
                SettingRow.intField("Aim Range", 5, 100, () -> cfg().aimRange,
                        value -> { cfg().aimRange = value; save(); }, "blocks")
                        .describe("How far ahead the preview looks for the block your throw would "
                                + "land on."),
                SettingRow.intField("Blast Radius", 1, 10, () -> cfg().radius,
                        value -> { cfg().radius = value; save(); }, "blocks")
                        .describe("Fallback radius, used only when the pickaxe's own description "
                                + "does not state one. The ability is 3 blocks at every level, so "
                                + "this rarely needs changing."));
    }
}
