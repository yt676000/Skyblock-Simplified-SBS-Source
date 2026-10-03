/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.fairysouls.logic;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.core.data.VersionedDataStore;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.helper.fairysouls.model.FairySoul;
import sbs.modid.client.helper.fairysouls.model.FairySoulData;

import java.util.List;

/**
 * The Fairy Soul coordinates: bundled, cached and backend-refreshed through the shared
 * {@link VersionedDataStore}.
 *
 * <p>Nothing here knows what has been collected - that is {@link FairySoulStore}'s job, and keeping
 * the two apart is what lets the coordinate file be replaced wholesale without touching a player's
 * progress.
 */
public final class FairySoulDatabase {

    private static final String RESOURCE =
            "/assets/" + SkyblockSimplifiedSBS.MOD_ID + "/fairysouls/souls.json";

    /** The highest schema this build reads; bump only alongside a parser change. */
    private static final int SUPPORTED_SCHEMA = 1;

    private static final VersionedDataStore<FairySoulData> STORE = new VersionedDataStore<>(
            "FairySouls", RESOURCE,
            SBSFiles.root().resolve("data").resolve("fairysouls.json"),
            "/api/fairysouls", FairySoulData.class, SUPPORTED_SCHEMA);

    /** The document the last {@link #link} ran over, so linking happens once per new document. */
    private static FairySoulData linked;

    private FairySoulDatabase() {
    }

    /** Loads bundled + cached copies and starts the background refresh. Call on client init. */
    public static void load() {
        STORE.load();
        link();
    }

    /**
     * The live document, with its island back-references filled in.
     *
     * <p>Linking is done lazily here rather than inside the store because the store is generic - it
     * knows nothing about souls. Checking identity makes this free on every call but the first after
     * a refresh lands.
     */
    private static FairySoulData data() {
        FairySoulData document = STORE.get();
        if (document != null && document != linked) {
            link();
        }
        return document;
    }

    private static synchronized void link() {
        FairySoulData document = STORE.get();
        if (document == null || document == linked) {
            return;
        }
        document.link();
        linked = document;
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][FairySouls] {} soul(s) across {} island(s), data v{}",
                document.all().size(), document.islands.size(), document.dataVersion());
    }

    /** The merged per-island list last handed out, so render-loop callers get a cached answer. */
    private static List<FairySoul> mergedCache = List.of();
    private static String mergedIsland;
    private static FairySoulData mergedDocument;
    private static int mergedGeneration = -1;

    /**
     * Every soul on {@code island}: the curated file's entries plus everything
     * {@link FairySoulLearned} has seen there. Merging here, at the single point every consumer
     * reads through, is what lets the tracker, router and renderer stay oblivious to where a
     * coordinate came from - and what makes an empty curated file a degraded state instead of a
     * dead feature. Cached per island because the renderer asks every frame.
     */
    public static synchronized List<FairySoul> forIsland(String island) {
        if (island == null || island.isBlank()) {
            return List.of();
        }
        FairySoulData document = data();
        FairySoulLearned learned = FairySoulLearned.getInstance();
        if (island.equals(mergedIsland) && document == mergedDocument
                && learned.generation() == mergedGeneration) {
            return mergedCache;
        }
        List<FairySoul> curated = curatedForIsland(island);
        List<FairySoul> seen = learned.forIsland(island, curated);
        List<FairySoul> merged;
        if (seen.isEmpty()) {
            merged = curated;
        } else if (curated.isEmpty()) {
            merged = seen;
        } else {
            merged = new java.util.ArrayList<>(curated.size() + seen.size());
            merged.addAll(curated);
            merged.addAll(seen);
        }
        mergedIsland = island;
        mergedDocument = document;
        mergedGeneration = learned.generation();
        mergedCache = merged;
        return merged;
    }

    /** Only the curated file's souls on {@code island} - the cross-check learned souls answer to. */
    public static List<FairySoul> curatedForIsland(String island) {
        FairySoulData document = data();
        if (document != null && island != null) {
            for (FairySoulData.Island entry : document.islands) {
                if (island.equalsIgnoreCase(entry.island)) {
                    return entry.souls;
                }
            }
        }
        return List.of();
    }

    /**
     * The souls on the island the player is standing on.
     *
     * <p>Island-level via {@link SkyBlockLocation#island()}, because that is the level at which
     * coordinates are meaningful - a zone is not an island and every zone shares its island's space.
     */
    public static List<FairySoul> onCurrentIsland() {
        return forIsland(SkyBlockLocation.island());
    }

    /**
     * The islands a Quest Log tile named {@code tile} speaks for.
     *
     * <p>Usually one, matched on the island's own name. The exception is a tile that groups several
     * islands - the guide's single "Galatea" covers both Moonglade Marsh and Torrhus Canyon - which
     * the data file declares through {@code questLogName}. Returning a list rather than a name is
     * what lets the caller decline to split a grouped tile's count between its islands.
     */
    public static List<String> islandsForQuestLogTile(String tile) {
        FairySoulData document = data();
        if (document == null || tile == null || tile.isBlank()) {
            return List.of();
        }
        List<String> out = new java.util.ArrayList<>(1);
        for (FairySoulData.Island island : document.islands) {
            if (tile.equalsIgnoreCase(island.island)
                    || tile.equalsIgnoreCase(island.questLogName)) {
                out.add(island.island);
            }
        }
        return out;
    }

    /** The soul with this id, or {@code null}. */
    public static FairySoul byId(String id) {
        FairySoulData document = data();
        if (document == null || id == null) {
            return null;
        }
        for (FairySoulData.Island island : document.islands) {
            for (FairySoul soul : island.souls) {
                if (id.equals(soul.id)) {
                    return soul;
                }
            }
        }
        return null;
    }

    /** Every catalogued soul, across all islands. */
    public static List<FairySoul> all() {
        FairySoulData document = data();
        return document == null ? List.of() : document.all();
    }

    /** Whether any coordinates are loaded at all. */
    public static boolean hasData() {
        return data() != null;
    }

    /**
     * The generator's count of every soul in the game, or {@code 0} when unknown.
     *
     * <p>Deliberately the file's own number rather than {@code all().size()}: with partial coverage
     * those differ, and the reconciliation line has to compare the API's account-wide total against
     * the real game total, not against how much of it we happen to ship.
     */
    public static int totalInGame() {
        FairySoulData document = data();
        return document == null ? 0 : document.totalSouls;
    }

    /** Which copy is live and at what version, for the settings status line. */
    public static String status() {
        FairySoulData document = data();
        if (document == null) {
            return "no coordinate data loaded";
        }
        return document.all().size() + " souls, " + document.islands.size() + " islands ("
                + STORE.source() + " v" + document.dataVersion() + ")";
    }
}
