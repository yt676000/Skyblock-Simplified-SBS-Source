/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.foraging;

import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.ModuleSubgroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.skills.foraging.ui.WaypointPresetRows;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.List;

/**
 * The shipped location presets for Galatea: the honey trees, the honey hives, the two beacons and
 * the Critter Safari quest bells.
 *
 * <p><b>Named for the region rather than for honey</b>, because it is no longer only honey - the
 * beacons and the bells are landmarks with no harvest and no timer, and a card called "Honey"
 * containing them would be lying about what it holds. The Critter Safari is its own instance
 * entered from Torrhus Canyon rather than an island of the marsh, and its group lives on this page
 * for the same reason: the page holds the region's marker sets, not one island's.
 *
 * <p><b>Every row on this page is generated from the data file</b> by {@link WaypointPresetRows},
 * which is shared with the other preset pages. There is no per-group code anywhere: a new preset
 * group is a data change and its controls appear on their own. That is the property the whole design
 * exists for, and hard-coding even one group's row would quietly destroy it.
 *
 * <p><b>The page is chosen by the group, not by its island or its kind.</b> This one lists the groups
 * whose {@code module} names it - which is every group that names nothing, so a set with no opinion
 * lands here - and a group belonging to Critter Waypoints or Critter Finder must not appear here
 * whatever island it names and whatever it is. Kind cannot answer this: the Snoozle walls are a
 * landmark exactly as the beacons are, and they belong on another page.
 *
 * <p><b>Nothing is on by default</b> while the coordinates are unverified - see the status block at
 * the bottom of the page, which states the certainty rather than hiding it.
 */
public final class GalateaWaypointsModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public GalateaWaypointsModule() {
    }

    @Override
    public String id() {
        return "galatea_waypoints";
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
        return "Galatea Waypoints";
    }

    @Override
    public String description() {
        return "Marks the honey trees, honey hives and beacons on Moonglade Marsh and Torrhus "
                + "Canyon, and the quest bells in the Critter Safari";
    }

    @Override
    public int accentColor() {
        return 0xFFFFAA25;
    }

    @Override
    public List<SettingRow> settings() {
        return WaypointPresetRows.page("Galatea",
                "Shipped marker sets. Each group is independent and renders only on its own island.",
                id());
    }
}
