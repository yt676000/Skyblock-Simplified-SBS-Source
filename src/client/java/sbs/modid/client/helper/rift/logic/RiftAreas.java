/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.rift.logic;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.core.data.VersionedDataStore;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.helper.rift.model.RiftData;

import java.util.Locale;

/**
 * The Rift's per-area clock rules: bundled, cached and backend-refreshed through the shared
 * {@link VersionedDataStore}.
 *
 * <p>Matching is by <b>zone</b>, not island - the whole file is about places inside one island, and
 * the sidebar's {@code ⏣} line is the only thing that distinguishes them. A contains-match rather
 * than an exact one, because Hypixel decorates zone names ("Colosseum" turns up as part of longer
 * strings) and an area that is not in the file simply behaves as normal, which is the correct
 * fallback for an incomplete file.
 */
public final class RiftAreas {

    private static final String RESOURCE =
            "/assets/" + SkyblockSimplifiedSBS.MOD_ID + "/rift/areas.json";

    /** The highest schema this build reads; bump only alongside a parser change. */
    private static final int SUPPORTED_SCHEMA = 1;

    private static final VersionedDataStore<RiftData> STORE = new VersionedDataStore<>(
            "RiftAreas", RESOURCE,
            SBSFiles.root().resolve("data").resolve("rift_areas.json"),
            "/api/rift/areas", RiftData.class, SUPPORTED_SCHEMA);

    private static RiftData linked;

    private RiftAreas() {
    }

    /** Loads bundled + cached copies and starts the background refresh. Call on client init. */
    public static void load() {
        STORE.load();
        link();
    }

    private static RiftData data() {
        RiftData document = STORE.get();
        if (document != null && document != linked) {
            link();
        }
        return document;
    }

    private static synchronized void link() {
        RiftData document = STORE.get();
        if (document == null || document == linked) {
            return;
        }
        document.link();
        linked = document;
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Rift] {} area rule(s), data v{}",
                document.areas.size(), document.dataVersion());
    }

    /** The rules for the zone the player is standing in, or {@code null} when it has none. */
    public static RiftData.Area current() {
        return forZone(SkyBlockLocation.zone());
    }

    /** The rules for a named zone, or {@code null}. */
    public static RiftData.Area forZone(String zone) {
        RiftData document = data();
        if (document == null || zone == null || zone.isBlank()) {
            return null;
        }
        String needle = zone.toLowerCase(Locale.ROOT);
        for (RiftData.Area area : document.areas) {
            String name = area.zone.toLowerCase(Locale.ROOT);
            if (needle.contains(name) || name.contains(needle)) {
                return area;
            }
        }
        return null;
    }

    /**
     * How fast the clock is running where the player stands - {@code 1.0} for an area with no rule,
     * which is both the default and the truth for most of the Rift.
     */
    public static double currentDrain() {
        RiftData.Area area = current();
        return area == null ? 1.0 : area.drainMultiplier;
    }

    /** Which copy is live and at what version, for the settings status line. */
    public static String status() {
        RiftData document = data();
        if (document == null) {
            return "no area data loaded";
        }
        return document.areas.size() + " areas (" + STORE.source() + " v"
                + document.dataVersion() + ")";
    }
}
