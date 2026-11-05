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
 * Solved Arrow Align layouts, captured by the {@code [SBS][ArrowAlign] CAPTURE} probe line in our own
 * runs. Ships empty until captures exist. Each entry's {@code rows} is the {@link ArrowAlignGrid}
 * text form of the device at the moment it completed.
 */
public final class ArrowAlignData implements VersionedDocument {

    public int schemaVersion = 1;
    public int dataVersion;
    public String generatedAt = "";

    public List<Layout> layouts = new ArrayList<>();

    /** One solved device. */
    public static final class Layout {
        /** Stable id; the probe derives it from the grid ({@code capture-<hash>}). Never renumbered. */
        public String id = "";
        /** The solved grid, top row first - see {@link ArrowAlignGrid#parse}. */
        public List<String> rows = new ArrayList<>();
        /** CONFIRMED = captured while the grid was being worked; ESTIMATED = timing was ambiguous. */
        public Certainty certainty = Certainty.UNKNOWN;
        /** Capture date, {@code YYYY-MM-DD}. */
        public String captured = "";
        public String note = "";
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
        return layouts != null;
    }
}
