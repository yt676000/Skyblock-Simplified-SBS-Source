/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.floorseven.logic;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.core.data.VersionedDataStore;
import sbs.modid.client.dungeons.floorseven.model.ArrowAlignData;
import sbs.modid.client.dungeons.floorseven.model.ArrowAlignGrid;

import java.util.ArrayList;
import java.util.List;

/**
 * The captured Arrow Align layouts: {@link ArrowAlignData} through {@link VersionedDataStore},
 * bundled-only (no backend route exists), parsed once per loaded document.
 */
public final class ArrowAlignLayouts {

    private static final String RESOURCE =
            "/assets/" + SkyblockSimplifiedSBS.MOD_ID + "/dungeons/f7_arrow_align.json";

    /** The highest schema this build reads; bump only alongside a parser change. */
    private static final int SUPPORTED_SCHEMA = 1;

    private static final VersionedDataStore<ArrowAlignData> STORE = new VersionedDataStore<>(
            "ArrowAlign", RESOURCE,
            SBSFiles.root().resolve("data").resolve("f7_arrow_align.json"),
            null, ArrowAlignData.class, SUPPORTED_SCHEMA);

    private static volatile ArrowAlignData indexed;
    private static volatile List<ArrowAlignSolver.Target> targets = List.of();

    private ArrowAlignLayouts() {
    }

    /** Loads bundled + cached copies. Call on client init. */
    public static void load() {
        STORE.load();
        targets();
    }

    /** The parsed layouts; re-parsed only when a different document is loaded. */
    public static List<ArrowAlignSolver.Target> targets() {
        ArrowAlignData document = STORE.get();
        if (document != indexed) {
            targets = parse(document);
            indexed = document;
        }
        return targets;
    }

    static List<ArrowAlignSolver.Target> parse(ArrowAlignData document) {
        if (document == null || document.layouts == null) {
            return List.of();
        }
        List<ArrowAlignSolver.Target> out = new ArrayList<>();
        for (ArrowAlignData.Layout layout : document.layouts) {
            ArrowAlignGrid grid = layout == null ? null : ArrowAlignGrid.parse(layout.rows);
            if (grid == null) {
                SkyblockSimplifiedSBS.LOGGER.warn("[SBS][ArrowAlign] skipping unreadable layout {}",
                        layout == null ? "null" : layout.id);
                continue;
            }
            out.add(new ArrowAlignSolver.Target(layout.id, grid));
        }
        return List.copyOf(out);
    }
}
