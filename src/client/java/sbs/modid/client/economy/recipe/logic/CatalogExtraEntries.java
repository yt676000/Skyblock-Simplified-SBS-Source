/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.recipe.logic;

import sbs.modid.client.combat.mobhighlight.model.SkyblockMobCatalog;
import sbs.modid.client.helper.npc.SkyblockNpcs;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Registers the non-item entries the Recipe Viewer search should also find: <b>NPCs</b> (from the
 * {@link SkyblockNpcs} location table – shift-clicking one opens the NPC locator) and <b>mobs</b>
 * (from the Mob Highlight catalogue, tagged with the area they live in). They ride the existing
 * supplemental-entry mechanism ({@link SkyBlockItemCatalog#addRepoEntries}), so official items
 * always win on a name collision and everything (search, icons, click handling) works unchanged.
 * Both are tagged with a synthetic category ({@code NPC} / {@code MOB}), which is what the Recipe
 * Viewer's {@link ItemFilter#MOBS_NPCS} button selects on.
 *
 * <p>Called once at client init; both sources are static tables, so there is nothing to refresh.
 */
public final class CatalogExtraEntries {

    private CatalogExtraEntries() {
    }

    /** Builds and registers the NPC + mob entries. */
    public static void register() {
        Map<String, SkyBlockItemCatalog.Entry> extras = new HashMap<>();

        // NPCs: "(NPC)" in the name is what the NPC locator's shift-click detection keys on.
        for (SkyblockNpcs.Npc npc : SkyblockNpcs.all()) {
            String id = "NPC_" + npc.name().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
            extras.put(id, new SkyBlockItemCatalog.Entry(
                    id, npc.name() + " (NPC)", "player_head", null, null, "NPC"));
        }

        // Mobs: tagged "(Mob)" so they read as lookups, not obtainable items.
        for (SkyblockMobCatalog.SkyblockMob mob : SkyblockMobCatalog.all()) {
            String id = "MOB_" + mob.name().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
            extras.put(id, new SkyBlockItemCatalog.Entry(
                    id, mob.name() + " (Mob)", "zombie_spawn_egg", null, null, "MOB"));
        }

        SkyBlockItemCatalog.getInstance().addRepoEntries(extras);
    }
}
