/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.fairysouls.model;

import sbs.modid.client.core.data.VersionedDocument;

import java.util.ArrayList;
import java.util.List;

/**
 * The Fairy Soul data file: every catalogued soul, grouped by island.
 *
 * <p>Matches the agreed schema exactly - {@code schemaVersion} / {@code dataVersion} at the top, then
 * one block per island. The backend owns the contents; this build only reads them.
 *
 * <p><b>Partial coverage is a normal state.</b> A file carrying five islands is a first pass, and
 * every consumer already asks per island, so the rest simply have nothing to show. Only a file with
 * no islands at all counts as unusable.
 */
public final class FairySoulData implements VersionedDocument {

    public int schemaVersion = 1;
    public int dataVersion;
    public String generatedAt = "";

    /** The generator's own count of every soul in the game, for the reconciliation line. */
    public int totalSouls;

    public List<Island> islands = new ArrayList<>();

    /** One island's souls. */
    public static final class Island {

        /**
         * The island name as {@link sbs.modid.client.core.location.SkyBlockLocation#island()} spells
         * it ("The Farming Islands"). The gate every waypoint publish uses - coordinates only mean
         * something on their own island.
         */
        public String island = "";

        /**
         * What the Quest Log's Fairy Souls Guide calls this island, when it differs from
         * {@link #island} - blank means the guide uses the same name.
         *
         * <p>Exists because the guide does not always tile one-to-one with islands: it shows a
         * single "Galatea" tile for what the tab list reports as two separate islands, Moonglade
         * Marsh and Torrhus Canyon. Without the alias that tile matches neither and its progress is
         * silently discarded.
         */
        public String questLogName = "";

        /** The generator's count for this island; {@link #souls} is what is actually drawn. */
        public int count;

        public List<FairySoul> souls = new ArrayList<>();

        public Island() {
        }
    }

    public FairySoulData() {
    }

    @Override
    public int schemaVersion() {
        return schemaVersion;
    }

    @Override
    public int dataVersion() {
        return dataVersion;
    }

    @Override
    public boolean valid() {
        return islands != null && !islands.isEmpty();
    }

    /**
     * Finishes parsing: drops unusable entries and stamps each soul with its island.
     *
     * <p>Called once when a document becomes live. The island back-reference is what lets a soul be
     * handed around on its own (to the router, to the store) without carrying its group with it.
     */
    public void link() {
        if (islands == null) {
            islands = new ArrayList<>();
            return;
        }
        islands.removeIf(island -> island == null || island.island == null || island.island.isBlank());
        for (Island island : islands) {
            if (island.souls == null) {
                island.souls = new ArrayList<>();
            }
            island.souls.removeIf(soul -> soul == null || !soul.valid());
            for (FairySoul soul : island.souls) {
                soul.island = island.island;
            }
        }
    }

    /** Every soul across every island. */
    public List<FairySoul> all() {
        List<FairySoul> out = new ArrayList<>();
        for (Island island : islands) {
            out.addAll(island.souls);
        }
        return out;
    }
}
