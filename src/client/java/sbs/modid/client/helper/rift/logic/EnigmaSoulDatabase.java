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
import sbs.modid.client.helper.rift.model.EnigmaSoul;
import sbs.modid.client.helper.rift.model.EnigmaSoulData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Enigma Soul coordinates and instructions: bundled, cached and backend-refreshed through the
 * shared {@link VersionedDataStore}.
 *
 * <p>Nothing here knows what has been collected - that is {@link EnigmaSoulStore}'s job, and keeping
 * the two apart is what lets the coordinate file be replaced wholesale without touching a player's
 * progress.
 */
public final class EnigmaSoulDatabase {

    private static final String RESOURCE =
            "/assets/" + SkyblockSimplifiedSBS.MOD_ID + "/rift/enigma_souls.json";

    /** The highest schema this build reads; bump only alongside a parser change. */
    private static final int SUPPORTED_SCHEMA = 1;

    private static final VersionedDataStore<EnigmaSoulData> STORE = new VersionedDataStore<>(
            "EnigmaSouls", RESOURCE,
            SBSFiles.root().resolve("data").resolve("enigma_souls.json"),
            "/api/rift/souls", EnigmaSoulData.class, SUPPORTED_SCHEMA);

    private static EnigmaSoulData linked;

    private EnigmaSoulDatabase() {
    }

    /** Loads bundled + cached copies and starts the background refresh. Call on client init. */
    public static void load() {
        STORE.load();
        link();
    }

    private static EnigmaSoulData data() {
        EnigmaSoulData document = STORE.get();
        if (document != null && document != linked) {
            link();
        }
        return document;
    }

    private static synchronized void link() {
        EnigmaSoulData document = STORE.get();
        if (document == null || document == linked) {
            return;
        }
        document.link();
        linked = document;
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Rift] {} of {} enigma soul(s) catalogued, data v{}",
                document.souls.size(), document.totalSouls, document.dataVersion());
    }

    /** Every catalogued soul. */
    public static List<EnigmaSoul> all() {
        EnigmaSoulData document = data();
        return document == null ? List.of() : document.souls;
    }

    /** The soul with this id, or {@code null}. */
    public static EnigmaSoul byId(String id) {
        if (id == null) {
            return null;
        }
        for (EnigmaSoul soul : all()) {
            if (id.equals(soul.id)) {
                return soul;
            }
        }
        return null;
    }

    /**
     * The soul whose {@link EnigmaSoul#apiName} matches, or {@code null}.
     *
     * <p>Case-insensitive because the API's spelling and a hand-entered mapping will not agree on it,
     * and a mapping that fails on capitalisation is worse than none - it looks like it works.
     */
    public static EnigmaSoul byApiName(String apiName) {
        if (apiName == null || apiName.isBlank()) {
            return null;
        }
        for (EnigmaSoul soul : all()) {
            if (apiName.equalsIgnoreCase(soul.apiName)) {
                return soul;
            }
        }
        return null;
    }

    /**
     * The one catalogued soul within {@code radius} of a point, or {@code null} when none or more
     * than one is - the same "never on a guess" rule the Fairy Souls use.
     *
     * <p>Ambiguity has to mean "no", not "the nearest": two souls a few blocks apart happen in the
     * Rift (the Cake House has one per floor), and picking the closer one on a pickup would record
     * the wrong id half the time and permanently hide a soul the player still needs.
     */
    public static EnigmaSoul soleNear(double x, double y, double z, double radius) {
        double limit = radius * radius;
        EnigmaSoul found = null;
        for (EnigmaSoul soul : all()) {
            double dx = soul.x + 0.5 - x;
            double dy = soul.y + 0.5 - y;
            double dz = soul.z + 0.5 - z;
            if (dx * dx + dy * dy + dz * dz <= limit) {
                if (found != null) {
                    return null;
                }
                found = soul;
            }
        }
        return found;
    }

    /** The catalogued souls grouped by their area, in file order, for the settings breakdown. */
    public static Map<String, List<EnigmaSoul>> byArea() {
        Map<String, List<EnigmaSoul>> out = new LinkedHashMap<>();
        for (EnigmaSoul soul : all()) {
            String area = soul.area == null || soul.area.isBlank() ? "Elsewhere" : soul.area;
            out.computeIfAbsent(area, key -> new ArrayList<>()).add(soul);
        }
        return out;
    }

    /** Every distinct Rift Guide section the file names, lower-cased, for matching the guide menu. */
    public static List<String> guideSections() {
        List<String> out = new ArrayList<>();
        for (EnigmaSoul soul : all()) {
            String section = soul.guideSection == null || soul.guideSection.isBlank()
                    ? soul.area : soul.guideSection;
            if (section != null && !section.isBlank()) {
                String key = section.toLowerCase(Locale.ROOT);
                if (!out.contains(key)) {
                    out.add(key);
                }
            }
        }
        return out;
    }

    /**
     * How many souls exist in the game, per the data file, or {@code 0} when unknown.
     *
     * <p>Deliberately the file's own number rather than {@code all().size()}: with partial coverage
     * those differ, and the reconciliation has to compare the chat line's count against the real
     * total, not against how much of it we happen to ship.
     */
    public static int totalInGame() {
        EnigmaSoulData document = data();
        return document == null ? 0 : document.totalSouls;
    }

    /** Whether any coordinates are loaded at all. */
    public static boolean hasData() {
        return data() != null;
    }

    /** Which copy is live and at what version, for the settings status line. */
    public static String status() {
        EnigmaSoulData document = data();
        if (document == null) {
            return "no soul data loaded";
        }
        return document.souls.size() + " of " + document.totalSouls + " souls ("
                + STORE.source() + " v" + document.dataVersion() + ")";
    }
}
