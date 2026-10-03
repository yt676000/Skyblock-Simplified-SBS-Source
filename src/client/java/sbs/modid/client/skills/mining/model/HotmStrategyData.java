/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.model;

import sbs.modid.client.core.data.VersionedDocument;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The advisor's goal profiles ({@code hotm-strategies.json}). A profile weights effect <i>stats</i>,
 * not perks, so a perk added to {@code hotm.json} that moves a weighted stat is ranked with no edit
 * here. {@code core}/{@code fill}/{@code skip} carry the recommended tree and the reasons shown.
 */
public final class HotmStrategyData implements VersionedDocument {

    public int schemaVersion = 1;
    public int dataVersion;
    public String generatedAt = "";
    public List<Profile> profiles = new ArrayList<>();

    public static final class Profile {
        public String id = "";
        public String name = "";
        /** A label short enough for a five-way segmented switch. */
        public String shortName = "";
        public String certainty = "ESTIMATED";
        public List<String> sources = new ArrayList<>();
        /** Value of one unit of a stat for this goal, relative to one Mining Speed. */
        public Map<String, Double> weights = new LinkedHashMap<>();
        public List<Pick> core = new ArrayList<>();
        public List<Pick> fill = new ArrayList<>();
        public List<Pick> skip = new ArrayList<>();

        public double weight(String stat) {
            Double weight = weights.get(stat);
            return weight == null ? 0 : weight;
        }

        public String label() {
            return shortName == null || shortName.isBlank() ? name : shortName;
        }
    }

    /** One perk in a profile's recommended tree (or skip list), with the reason shown. */
    public static final class Pick {
        public String perk = "";
        public int target;
        public String reason = "";
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
        return profiles != null && !profiles.isEmpty();
    }
}
