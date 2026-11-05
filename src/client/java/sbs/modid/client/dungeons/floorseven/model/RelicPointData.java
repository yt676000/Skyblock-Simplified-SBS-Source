/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.floorseven.model;

import sbs.modid.client.core.data.VersionedDocument;
import sbs.modid.client.helper.rift.model.Certainty;

import java.util.ArrayList;
import java.util.List;

/**
 * Where each M7 relic spawns and where it has to be taken, as bundled data
 * ({@code assets/.../dungeons/m7_relics.json}). Positions come only from our own
 * {@code [SBS][Relics]} captures; a point that has not been captured is absent, and absent means no
 * marker - never a guessed one.
 *
 * <p>Each point carries the {@link Certainty} it was entered at and the date of the capture it came
 * from, so a stale or single-run value is visible as such.
 */
public final class RelicPointData implements VersionedDocument {

    public int schemaVersion = 1;
    public int dataVersion;
    public String generatedAt = "";

    public List<Entry> relics = new ArrayList<>();

    /** One relic: {@code colour} is a {@link RelicColour#name()}. */
    public static final class Entry {
        public String colour = "";
        public Point spawn;
        public Point target;
    }

    /** A block position with its provenance. */
    public static final class Point {
        public double x;
        public double y;
        public double z;
        public Certainty certainty = Certainty.UNKNOWN;
        /** {@code YYYY-MM-DD} of the capture this came from. */
        public String captured = "";
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
        return relics != null;
    }
}
