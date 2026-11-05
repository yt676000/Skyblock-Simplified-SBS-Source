/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.foraging.model;

import sbs.modid.client.core.data.VersionedDocument;
import sbs.modid.client.helper.rift.model.Certainty;

import java.util.ArrayList;
import java.util.List;

/**
 * The Heart of the Forest layout: each perk's (tier, menu column) and level cap, recorded from the
 * real menu. Positions are captured; a perk's id is derived from its name and stays {@code ESTIMATED}
 * until the profile API has been seen to use it.
 */
public final class HotfData implements VersionedDocument {

    public int schemaVersion = 1;
    public int dataVersion;
    public String generatedAt = "";
    public int maxTier;
    public List<Perk> perks = new ArrayList<>();

    /** One perk node. */
    public static final class Perk {
        public String id = "";
        public String name = "";
        public int tier;
        /** Menu column (slot % 9). */
        public int column = -1;
        public int maxLevel;
        /** Whether {@link #id} is the key the profile API uses. */
        public String idCertainty = Certainty.ESTIMATED.name();

        public Certainty idCertainty() {
            try {
                return Certainty.valueOf(idCertainty);
            } catch (IllegalArgumentException | NullPointerException e) {
                return Certainty.UNKNOWN;
            }
        }
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
        return perks != null && !perks.isEmpty();
    }
}
