/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.minions.logic;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.core.data.VersionedDataStore;
import sbs.modid.client.economy.minions.model.MinionData;
import sbs.modid.client.economy.minions.model.MinionModifierData;

import java.util.HashMap;
import java.util.Map;

/**
 * The two minion datasets - catalog and modifiers - behind the shared {@link VersionedDataStore}
 * loading path (bundled copy live immediately, cached copy on top, backend refresh once the
 * routes exist; both endpoints are {@code null} until then, i.e. bundled-only).
 *
 * <p>This is a MIGRATE-class dataset by the data-externalization classification: it changes
 * whenever Hypixel touches minions, and a change ships as a data bump
 * ({@code scripts/minion_catalog_import.py}), not a mod release.
 */
public final class MinionCatalogs {

    /** The highest schema this build reads; bump only alongside a model change. */
    private static final int SUPPORTED_SCHEMA = 1;

    private static final VersionedDataStore<MinionData> MINIONS = new VersionedDataStore<>(
            "Minions",
            "/assets/" + SkyblockSimplifiedSBS.MOD_ID + "/minions/minions.json",
            SBSFiles.root().resolve("data").resolve("minions.json"),
            null, MinionData.class, SUPPORTED_SCHEMA);

    private static final VersionedDataStore<MinionModifierData> MODIFIERS = new VersionedDataStore<>(
            "MinionModifiers",
            "/assets/" + SkyblockSimplifiedSBS.MOD_ID + "/minions/minion-modifiers.json",
            SBSFiles.root().resolve("data").resolve("minion-modifiers.json"),
            null, MinionModifierData.class, SUPPORTED_SCHEMA);

    /** The document the last link ran over, so sanitizing happens once per new document. */
    private static MinionData linked;

    /** Per-document index: minion type -> entry, rebuilt when a new document lands. */
    private static Map<String, MinionData.Minion> byType = Map.of();
    private static Map<String, MinionData.XpRow> xpByItem = Map.of();

    private MinionCatalogs() {
    }

    /** Loads bundled + cached copies. Call on client init. */
    public static void load() {
        MINIONS.load();
        MODIFIERS.load();
        link();
    }

    /** The live catalog, sanitized and indexed, or {@code null} when even the bundled copy failed. */
    public static MinionData minions() {
        MinionData document = MINIONS.get();
        if (document != null && document != linked) {
            link();
        }
        return document;
    }

    /** The live modifier tables, or {@code null} when even the bundled copy failed. */
    public static MinionModifierData modifiers() {
        return MODIFIERS.get();
    }

    /** The minion entry for a generator type key ("SNOW"), or {@code null}. */
    public static MinionData.Minion byType(String type) {
        minions();
        return type == null ? null : byType.get(type);
    }

    /** The XP fact for an item id, or {@code null} when the figure is unknown (never zero). */
    public static MinionData.XpRow xpFor(String itemId) {
        minions();
        return itemId == null ? null : xpByItem.get(itemId);
    }

    private static synchronized void link() {
        MinionData document = MINIONS.get();
        if (document == null || document == linked) {
            return;
        }
        document.link();
        Map<String, MinionData.Minion> types = new HashMap<>();
        for (MinionData.Minion minion : document.minions) {
            types.put(minion.type, minion);
        }
        Map<String, MinionData.XpRow> xp = new HashMap<>();
        for (MinionData.XpRow row : document.xp) {
            xp.put(row.item, row);
        }
        byType = Map.copyOf(types);
        xpByItem = Map.copyOf(xp);
        linked = document;
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Minions] {} type(s), {} XP fact(s), {} chain(s), data v{}",
                document.minions.size(), document.xp.size(), document.compactChains.size(),
                document.dataVersion());
    }
}
