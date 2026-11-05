/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.enchants.counter;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.core.data.VersionedDataStore;

import java.util.List;

/**
 * The loaded counter enchant table. Bundled only for now ({@code null} endpoint): the thresholds are
 * wiki values and the counter keys unverified, which is why they are a document - a capture corrects
 * them with a JSON edit rather than a code change.
 */
public final class CounterEnchants {

    private static final String RESOURCE =
            "/assets/" + SkyblockSimplifiedSBS.MOD_ID + "/enchants/counter_enchants.json";

    /** The highest schema this build reads; bump only alongside a parser change. */
    private static final int SUPPORTED_SCHEMA = 1;

    private static final VersionedDataStore<CounterEnchantData> STORE = new VersionedDataStore<>(
            "CounterEnchants", RESOURCE,
            SBSFiles.root().resolve("data").resolve("counter_enchants.json"),
            null, CounterEnchantData.class, SUPPORTED_SCHEMA);

    /** The usable rows of the live document, rebuilt when the store publishes a different one. */
    private static volatile List<CounterEnchantData.Entry> entries = List.of();
    private static volatile CounterEnchantData indexed;

    private CounterEnchants() {
    }

    /** Loads the bundled and cached copies. Call on client init. */
    public static void load() {
        STORE.load();
        reindex();
    }

    /** The usable rows; empty when nothing loaded. */
    public static List<CounterEnchantData.Entry> entries() {
        if (STORE.get() != indexed) {
            reindex();
        }
        return entries;
    }

    /** The loaded content version, for tooltip cache keys; {@code -1} when nothing loaded. */
    public static int version() {
        return STORE.version();
    }

    private static synchronized void reindex() {
        CounterEnchantData document = STORE.get();
        indexed = document;
        entries = CounterEnchantData.usable(document);
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][CounterEnchant] table loaded: {} enchants ({})",
                entries.size(), STORE.source());
    }
}
