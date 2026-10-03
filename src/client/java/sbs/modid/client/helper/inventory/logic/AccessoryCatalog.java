/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.inventory.logic;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.core.data.VersionedDataStore;
import sbs.modid.client.helper.inventory.model.Accessory;
import sbs.modid.client.helper.inventory.model.AccessoryData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Every accessory that exists, loaded through the shared {@link VersionedDataStore}.
 *
 * <p>This is the half of the missing-accessory feature the client cannot work out for itself. An
 * Accessory Bag only ever shows what a player already has, so "what am I missing" is unanswerable
 * without a list of what there is - and that list comes from Hypixel's own keyless item resource by
 * way of {@code scripts/accessories_import.py}.
 *
 * <p>Nothing here knows what anybody owns; that is {@link AccessoryIndex}. The split is what lets
 * the catalogue be replaced by a newer copy mid-session without disturbing a single player record.
 */
public final class AccessoryCatalog {

    private static final String RESOURCE =
            "/assets/" + SkyblockSimplifiedSBS.MOD_ID + "/accessories/accessories.json";

    /** The highest schema this build reads; bump only alongside a parser change. */
    private static final int SUPPORTED_SCHEMA = 1;

    private static final VersionedDataStore<AccessoryData> STORE = new VersionedDataStore<>(
            "Accessories", RESOURCE,
            SBSFiles.root().resolve("data").resolve("accessories.json"),
            "/api/accessories", AccessoryData.class, SUPPORTED_SCHEMA);

    /** The document the lookup tables below were built from, so they rebuild once per new copy. */
    private static AccessoryData linked;

    private static Map<String, Accessory> byId = Map.of();
    private static Map<String, List<Accessory>> byFamily = Map.of();

    private AccessoryCatalog() {
    }

    /** Loads bundled + cached copies and starts the background refresh. Call on client init. */
    public static void load() {
        STORE.load();
        link();
    }

    /** The live document with its lookup tables built, or {@code null} when nothing loaded. */
    private static AccessoryData data() {
        AccessoryData document = STORE.get();
        if (document != null && document != linked) {
            link();
        }
        return document;
    }

    /**
     * Rebuilds the id and family indexes from the live document.
     *
     * <p>Done here rather than in the store because the store is generic - it knows nothing about
     * accessories. The identity check makes every call after the first free.
     */
    private static synchronized void link() {
        AccessoryData document = STORE.get();
        if (document == null || document == linked) {
            return;
        }
        document.link();
        Map<String, Accessory> ids = new HashMap<>();
        Map<String, List<Accessory>> families = new HashMap<>();
        for (Accessory accessory : document.accessories) {
            ids.put(accessory.id, accessory);
            if (accessory.hasFamily()) {
                families.computeIfAbsent(accessory.family, key -> new ArrayList<>()).add(accessory);
            }
        }
        for (List<Accessory> members : families.values()) {
            members.sort((a, b) -> Integer.compare(a.step, b.step));
        }
        byId = Map.copyOf(ids);
        byFamily = Map.copyOf(families);
        linked = document;
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Accessories] {} accessor(ies) across {} upgrade ladder(s), data v{} ({})",
                ids.size(), families.size(), document.dataVersion(), STORE.source());
    }

    /** Every catalogued accessory, in the generator's order (id-sorted). Never {@code null}. */
    public static List<Accessory> all() {
        AccessoryData document = data();
        return document == null ? List.of() : Collections.unmodifiableList(document.accessories);
    }

    /** The accessory with this SkyBlock id, or {@code null} when it is not one (or not catalogued). */
    public static Accessory byId(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        data();
        return byId.get(id);
    }

    /** Whether this SkyBlock id is a catalogued accessory. */
    public static boolean isAccessory(String id) {
        return byId(id) != null;
    }

    /**
     * Every tier of an upgrade ladder, lowest step first. Empty for an accessory that stands alone -
     * which is the safe answer, since a standalone accessory is never superseded by anything.
     */
    public static List<Accessory> family(String family) {
        if (family == null || family.isBlank()) {
            return List.of();
        }
        data();
        return byFamily.getOrDefault(family, List.of());
    }

    /** Whether the catalogue is loaded at all, for the "unavailable" state rather than an empty list. */
    public static boolean loaded() {
        return data() != null;
    }

    /** Where the live catalogue came from and how old it is, for the settings status line. */
    public static String status() {
        AccessoryData document = data();
        if (document == null) {
            return "unavailable";
        }
        return all().size() + " accessories (" + STORE.source() + " v" + document.dataVersion() + ")";
    }
}
