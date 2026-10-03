/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.logic;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.core.data.VersionedDataStore;
import sbs.modid.client.skills.mining.model.HotmData;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loads the Heart of the Mountain perk catalogue through the shared versioned data path.
 *
 * <p>Bundled → cached → backend, same as every other curated table. The bundled copy carries perk
 * identities and no costs, on purpose: see the note at the top of {@code hotm.json}. Costs come from
 * the menu, and {@link HotmTreeReader} is what puts them there.
 *
 * <p><b>This is the loader only.</b> No ranking is built on it yet - the perk costs it is meant to
 * rank by are exactly what has not been observed, and a ranking over an empty cost table would be a
 * confident ordering of nothing.
 */
public final class HotmCatalog {

    /** The highest schema this build reads; bump only alongside a model change. */
    private static final int SUPPORTED_SCHEMA = 2;

    private static final VersionedDataStore<HotmData> STORE = new VersionedDataStore<>(
            "Hotm",
            "/assets/" + SkyblockSimplifiedSBS.MOD_ID + "/hotm/hotm.json",
            SBSFiles.root().resolve("data").resolve("hotm.json"),
            null, HotmData.class, SUPPORTED_SCHEMA);

    /** Per-document index: perk id -> entry, rebuilt when a new document lands. */
    private static Map<String, HotmData.Perk> byId = Map.of();
    private static HotmData indexed;

    private HotmCatalog() {
    }

    /** Loads bundled + cached copies. Call on client init. */
    public static void load() {
        STORE.load();
        index();
    }

    /** The live document, or {@code null} when even the bundled copy failed to parse. */
    public static HotmData data() {
        return STORE.get();
    }

    /** Where the live document came from, for the settings status line. */
    public static String source() {
        return STORE.source();
    }

    /** Every perk in the catalogue, in file order. Empty when nothing loaded. */
    public static List<HotmData.Perk> perks() {
        HotmData data = data();
        return data == null ? List.of() : data.perks;
    }

    /** The perk with this id, or {@code null}. Case-insensitive. */
    public static HotmData.Perk byId(String id) {
        index();
        return id == null ? null : byId.get(id.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * The perk whose display name matches, or {@code null}. This is the lookup the menu reader needs -
     * a tooltip gives a name, not an id.
     */
    public static HotmData.Perk byName(String name) {
        if (name == null) {
            return null;
        }
        String wanted = name.trim().toLowerCase(Locale.ROOT);
        for (HotmData.Perk perk : perks()) {
            if (perk.name != null && perk.name.trim().toLowerCase(Locale.ROOT).equals(wanted)) {
                return perk;
            }
        }
        return null;
    }

    /**
     * How many perks in the catalogue have a complete cost ladder.
     *
     * <p>The number the settings screen shows, because "the loader works" and "the loader has
     * anything worth ranking" are different questions and only the second one matters to a player.
     */
    public static int perksWithCosts() {
        int complete = 0;
        for (HotmData.Perk perk : perks()) {
            if (perk.maxLevel > 0 && perk.totalCost(0, perk.maxLevel) > 0) {
                complete++;
            }
        }
        return complete;
    }

    private static synchronized void index() {
        HotmData data = data();
        if (data == null || data == indexed) {
            return;
        }
        indexed = data;
        Map<String, HotmData.Perk> map = new LinkedHashMap<>();
        for (HotmData.Perk perk : data.perks) {
            if (perk != null && perk.id != null && !perk.id.isBlank()) {
                map.put(perk.id.trim().toLowerCase(Locale.ROOT), perk);
            }
        }
        byId = Map.copyOf(map);
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Hotm] catalogue: {} perk(s), {} with a complete cost ladder (source {})",
                data.perks.size(), perksWithCosts(), source());
    }
}
