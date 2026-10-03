/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.farming.model;

import sbs.modid.client.core.data.VersionedDocument;
import sbs.modid.client.helper.rift.model.Certainty;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The rare drops that come from breaking crops ({@code assets/.../farming/drops.json}). Wiki facts,
 * each tagged with how sure we are - nothing in it has been seen in a real log yet.
 */
public final class FarmDropData implements VersionedDocument {

    public int schemaVersion = 1;
    public int dataVersion;
    public List<Entry> drops = new ArrayList<>();

    /** One droppable item. */
    public static final class Entry {
        public String name = "";
        public String id = "";
        public Certainty certainty = Certainty.UNKNOWN;
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
        return drops != null && !drops.isEmpty();
    }

    /** Whether a parsed drop line is one of these farming drops. */
    public boolean isFarmingDrop(sbs.modid.client.core.util.RareDropLine.Drop drop) {
        return drop != null && byName(drop.item()) != null;
    }

    /** The entry whose name matches a chat line's item, ignoring case; {@code null} otherwise. */
    public Entry byName(String item) {
        if (item == null || drops == null) {
            return null;
        }
        String want = item.trim().toLowerCase(Locale.ROOT);
        for (Entry e : drops) {
            if (e.name != null && e.name.toLowerCase(Locale.ROOT).equals(want)) {
                return e;
            }
        }
        return null;
    }
}
