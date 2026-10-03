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
import sbs.modid.client.skills.mining.model.HotmStrategyData;

import java.util.List;

/** Loads the advisor's goal profiles through the shared versioned data path (bundled only). */
public final class HotmStrategies {

    private static final int SUPPORTED_SCHEMA = 1;

    private static final VersionedDataStore<HotmStrategyData> STORE = new VersionedDataStore<>(
            "HotmStrategies",
            "/assets/" + SkyblockSimplifiedSBS.MOD_ID + "/hotm/hotm-strategies.json",
            SBSFiles.root().resolve("data").resolve("hotm-strategies.json"),
            null, HotmStrategyData.class, SUPPORTED_SCHEMA);

    private HotmStrategies() {
    }

    public static void load() {
        STORE.load();
    }

    public static List<HotmStrategyData.Profile> profiles() {
        HotmStrategyData data = STORE.get();
        return data == null ? List.of() : data.profiles;
    }

    /** The profile at {@code index}, clamped; {@code null} when nothing loaded. */
    public static HotmStrategyData.Profile at(int index) {
        List<HotmStrategyData.Profile> all = profiles();
        return all.isEmpty() ? null : all.get(Math.max(0, Math.min(index, all.size() - 1)));
    }
}
