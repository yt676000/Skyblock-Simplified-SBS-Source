/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.hunting;

import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.ModuleSubgroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.skills.foraging.ui.WaypointPresetRows;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.List;

/**
 * The shipped critter spawn locations inside the Critter Safari, one toggle per critter.
 *
 * <p><b>A page, not a feature.</b> Nothing is drawn from here: the points live in the shipped preset
 * document, {@code WaypointPresetPublisher} publishes the groups that are switched on and belong to
 * the island underfoot, and the ordinary waypoint renderer draws them. This class chooses which
 * groups the page lists and what to call it, and {@link WaypointPresetRows} emits every row.
 *
 * <p><b>So the next critter is a data change.</b> Appending a group naming this module to
 * {@code waypoints/presets.json} gives it a toggle, a colour picker, its island scoping and its
 * markers with no Java at all. Anything that hard-codes one critter here takes that away, which is
 * the one thing this module must not do.
 *
 * <p><b>Why here and not on the Galatea page.</b> That module is named for its region and says so on
 * every row; a Critter Safari group listed under it would be wrong on the page. This sits with the
 * other three Critter Safari features - Critter Finder, Safari Summary, Pelt Tracker - which is
 * where a player looks for it. Self-registered via
 * {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 *
 * <p><b>The Safari gate is the group's island scope, resolved by
 * {@link sbs.modid.client.core.location.SkyBlockLocation#inCritterSafari()}.</b> Not
 * {@link sbs.modid.client.skills.hunting.logic.SafariTracker#inSafariArea()}, which carries a
 * configurable word as its rename fallback and is therefore the looser of the two - and not
 * {@code onIsland} either, whose zone-resolving fallback is a seed-table guess. The Safari is an
 * instance around its own origin, so a gate that can be talked into firing on Torrhus Canyon puts
 * these markers into another island's coordinate space at visibly wrong places. The group names the
 * island; the island is matched exactly; the entrance is refused.
 *
 * <p><b>Nothing is on by default</b> while the coordinates are unverified; the page says so.
 */
public final class CritterWaypointsModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public CritterWaypointsModule() {
    }

    @Override
    public String id() {
        return "critter_waypoints";
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
        return "Critter Waypoints";
    }

    @Override
    public String description() {
        return "Marks the known critter spawn locations inside the Critter Safari";
    }

    @Override
    public int accentColor() {
        return 0xFF00FF00;
    }

    @Override
    public List<SettingRow> settings() {
        return WaypointPresetRows.page("Critter Safari",
                "Known spawn locations, one group per critter. Each is independent and renders only "
                        + "inside the Critter Safari.",
                id());
    }
}
