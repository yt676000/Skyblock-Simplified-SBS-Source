/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.fishing.model;

import sbs.modid.client.helper.rift.model.Certainty;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which sea creature can drop which rare item - the table the Rare Drops section books against.
 *
 * <p>Only items that are worth attributing are listed: the creature-specific rare drops a player
 * cares about seeing on their own line. Common kill loot (Magmafish, Lump of Magma, pelts) stays in
 * the ordinary catch list and never needs a creature behind it.
 *
 * <p>Every entry carries its {@link Certainty}. Facts only, and few of them on purpose: an item id
 * comes from the keyless Hypixel items resource (the id and its name are checked there), the
 * creature-to-item link is either watched in game ({@code CONFIRMED}, with the session it was seen
 * in) or a public-wiki fact still waiting for that ({@code WIKI}). A creature whose drops nobody has
 * verified - Plhlegblast, Fiery Scuttler, Ragnarok - has no entry rather than a guessed one; the
 * attribution logger prints every unexplained pickup that follows a kill, so one trip fills the gap.
 */
public final class SeaCreatureDropTable {

    /** One creature-to-item link. */
    public record Drop(String creature, String itemId, Certainty certainty) {
    }

    private static final Map<String, List<Drop>> BY_ITEM = new HashMap<>();

    private SeaCreatureDropTable() {
    }

    private static void drop(String creature, String itemId, Certainty certainty) {
        BY_ITEM.computeIfAbsent(itemId, k -> new ArrayList<>()).add(new Drop(creature, itemId, certainty));
    }

    static {
        // Crimson Isle lava.
        // Seen 2026-10-08: the fragment arrived in the hotbar seconds after Lord Jawbus died
        // (menu capture 15:23:33 shows it, the 15:23:06 capture does not), with no chat line.
        drop("Lord Jawbus", "MAGMA_LORD_FRAGMENT", Certainty.CONFIRMED);
        drop("Lord Jawbus", "RADIOACTIVE_VIAL", Certainty.WIKI);
        // Seen 2026-10-08: one Thunder Fragment (id THUNDER_SHARDS, name "Thunder Fragment" in the
        // items resource) in the first "[Sacks]" summary after the Thunder kill.
        drop("Thunder", "THUNDER_SHARDS", Certainty.CONFIRMED);
        // Water.
        drop("Water Hydra", "WATER_HYDRA_HEAD", Certainty.WIKI);
        drop("Yeti", "YETI_ROD", Certainty.WIKI);
    }

    /** Whether {@code itemId} is a creature-attributed rare drop at all. */
    public static boolean isRareDrop(String itemId) {
        return itemId != null && BY_ITEM.containsKey(itemId);
    }

    /** The links naming {@code itemId}, empty when it is not a rare drop. */
    public static List<Drop> sourcesOf(String itemId) {
        List<Drop> drops = itemId == null ? null : BY_ITEM.get(itemId);
        return drops == null ? List.of() : drops;
    }

    /** Every creature that appears in the table - the ones whose deaths the ledger records. */
    public static Set<String> creatures() {
        Set<String> out = new java.util.LinkedHashSet<>();
        for (List<Drop> drops : BY_ITEM.values()) {
            for (Drop drop : drops) {
                out.add(drop.creature());
            }
        }
        return out;
    }
}
