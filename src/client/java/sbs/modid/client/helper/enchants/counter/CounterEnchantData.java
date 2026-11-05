/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.enchants.counter;

import sbs.modid.client.core.data.VersionedDocument;

import java.util.ArrayList;
import java.util.List;

/**
 * The counter enchant table ({@code enchants/counter_enchants.json}): enchants that level up from a
 * running total Hypixel keeps in the item's custom data - Champion and Toxophilite count Combat XP.
 *
 * <p>A row is pure description: which enchant, which custom-data key holds its total, and the
 * cumulative total each level needs. Adding Expertise or Compact is a new row, not new code.
 */
public final class CounterEnchantData implements VersionedDocument {

    public int schemaVersion = 1;
    public int dataVersion;
    public String generatedAt = "";

    public List<Entry> enchants = new ArrayList<>();

    /** One counter enchant. */
    public static final class Entry {
        /** The key in the item's {@code enchantments} compound ("champion"). */
        public String enchant = "";
        /** The custom-data key holding the running total ("champion_combat_xp"). */
        public String counterKey = "";
        /** What the total counts, shown after the number ("XP", "kills"). */
        public String unit = "";
        /** {@code CONFIRMED}, {@code WIKI} or {@code ESTIMATED} - see {@code Certainty}. */
        public String certainty = "WIKI";
        /** Where the thresholds come from. */
        public String source = "";
        /** Cumulative total needed for level 1, 2, 3, ...; the length is the max level. */
        public long[] thresholds = new long[0];

        /** The highest level the table knows. */
        public int maxLevel() {
            return thresholds == null ? 0 : thresholds.length;
        }

        boolean usable() {
            return enchant != null && !enchant.isBlank() && maxLevel() > 0;
        }
    }

    /** The rows of {@code document} that name an enchant and at least one level. */
    static List<Entry> usable(CounterEnchantData document) {
        if (document == null || document.enchants == null) {
            return List.of();
        }
        List<Entry> out = new ArrayList<>();
        for (Entry entry : document.enchants) {
            if (entry != null && entry.usable()) {
                out.add(entry);
            }
        }
        return List.copyOf(out);
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
        return enchants != null;
    }
}
