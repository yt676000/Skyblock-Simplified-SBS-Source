/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.foraging.logic;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.core.data.VersionedDataStore;
import sbs.modid.client.skills.foraging.model.HotfData;

import java.util.List;

/** The bundled Heart of the Forest layout ({@code hotf/hotf.json}); read by the Player Viewer. */
public final class HotfCatalog {

    /** The highest schema this build reads. */
    private static final int SUPPORTED_SCHEMA = 1;

    private static final VersionedDataStore<HotfData> STORE = new VersionedDataStore<>(
            "Hotf",
            "/assets/" + SkyblockSimplifiedSBS.MOD_ID + "/hotf/hotf.json",
            SBSFiles.root().resolve("data").resolve("hotf.json"),
            null, HotfData.class, SUPPORTED_SCHEMA);

    private HotfCatalog() {
    }

    /** Loads the bundled and cached copies. Call on client init. */
    public static void load() {
        STORE.load();
    }

    /** Every perk, or empty when nothing loaded. */
    public static List<HotfData.Perk> perks() {
        HotfData data = STORE.get();
        return data == null ? List.of() : data.perks;
    }
}
