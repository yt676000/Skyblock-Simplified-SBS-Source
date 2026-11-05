/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.floorseven.logic;

import sbs.modid.client.dungeons.floorseven.model.LightsCell;
import sbs.modid.client.dungeons.floorseven.model.LightsLayout;
import sbs.modid.client.dungeons.floorseven.model.LightsWiringData;
import sbs.modid.client.helper.rift.model.Certainty;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The wiring of one layout as lever columns: which lamps each lever toggles, and which levers that is
 * known for.
 *
 * <p>Two sources, merged per lever. A bundled capture matching the layout exactly gives every lever
 * at once, with its {@link Certainty} and capture date. What was watched in this run overrides it per
 * lever - the live world is the better witness, and a bundled entry that disagrees with it is logged
 * by the tracker, not trusted.
 *
 * <p>Bundled entries are matched only in the orientation they were captured in. A rotated or mirrored
 * build of the same device is not recognised and falls back to live learning.
 */
public record LightsWiring(long[] columns, long knownMask, Source source, Certainty certainty,
                           String capturedOn, String deviceId) {

    /** Where the wiring came from. */
    public enum Source {
        /** Nothing known. */
        NONE,
        /** Only what was watched this run. */
        LEARNED,
        /** A bundled capture, untouched. */
        BUNDLED,
        /** A bundled capture with some levers overridden by what was watched. */
        MIXED
    }

    /** Number of levers whose wiring is known. */
    public int knownCount() {
        return Long.bitCount(knownMask);
    }

    /** Whether every lever of the layout is known. */
    public boolean complete(int leverCount) {
        long all = leverCount >= 64 ? -1L : (1L << leverCount) - 1;
        return (knownMask & all) == all;
    }

    /**
     * Resolves the wiring of {@code layout}.
     *
     * @param learned absolute lever to the absolute lamps it toggled when last seen flipped alone
     * @param bundled the shipped captures; may be empty or {@code null}
     */
    public static LightsWiring resolve(LightsLayout layout, Map<LightsCell, Set<LightsCell>> learned,
                                       List<LightsWiringData.Device> bundled) {
        int levers = Math.min(layout.levers().size(), LightsLayout.MAX_CELLS);
        long[] columns = new long[levers];
        long known = 0L;
        Source source = Source.NONE;
        Certainty certainty = Certainty.UNKNOWN;
        String date = "";
        String id = "";

        LightsWiringData.Device match = match(layout, bundled);
        if (match != null) {
            List<LightsCell> relLevers = layout.relativeLevers();
            List<LightsCell> relLamps = layout.relativeLamps();
            Map<LightsCell, Integer> lampIndex = new HashMap<>();
            for (int j = 0; j < relLamps.size(); j++) {
                lampIndex.put(relLamps.get(j), j);
            }
            for (int k = 0; k < match.levers.size(); k++) {
                int i = relLevers.indexOf(cell(match.levers.get(k)));
                if (i < 0 || i >= levers || k >= match.toggles.size()) {
                    continue;
                }
                long column = 0L;
                for (Integer lamp : match.toggles.get(k)) {
                    if (lamp == null || lamp < 0 || lamp >= match.lamps.size()) {
                        continue;
                    }
                    Integer j = lampIndex.get(cell(match.lamps.get(lamp)));
                    if (j != null && j < LightsLayout.MAX_CELLS) {
                        column |= 1L << j;
                    }
                }
                columns[i] = column;
                known |= 1L << i;
            }
            source = Source.BUNDLED;
            certainty = match.certainty == null ? Certainty.UNKNOWN : match.certainty;
            date = match.capturedOn == null ? "" : match.capturedOn;
            id = match.id == null ? "" : match.id;
        }

        boolean overrode = false;
        for (Map.Entry<LightsCell, Set<LightsCell>> entry : learned.entrySet()) {
            int i = layout.leverIndex(entry.getKey());
            if (i < 0 || i >= levers) {
                continue;
            }
            long column = layout.lampMask(entry.getValue());
            if ((known & (1L << i)) != 0 && columns[i] != column) {
                overrode = true;
            }
            columns[i] = column;
            known |= 1L << i;
        }
        if (!learned.isEmpty()) {
            if (source == Source.BUNDLED) {
                source = overrode ? Source.MIXED : Source.BUNDLED;
            } else if (known != 0) {
                source = Source.LEARNED;
                certainty = Certainty.ESTIMATED;
            }
        }
        return new LightsWiring(columns, known, source, certainty, date, id);
    }

    /** The bundled device whose lever and lamp sets equal this layout's, relative to the anchor. */
    static LightsWiringData.Device match(LightsLayout layout, List<LightsWiringData.Device> bundled) {
        if (bundled == null || bundled.isEmpty()) {
            return null;
        }
        Set<LightsCell> levers = new HashSet<>(layout.relativeLevers());
        Set<LightsCell> lamps = new HashSet<>(layout.relativeLamps());
        for (LightsWiringData.Device device : bundled) {
            if (device == null || device.levers == null || device.lamps == null || device.toggles == null) {
                continue;
            }
            if (levers.equals(cells(device.levers)) && lamps.equals(cells(device.lamps))) {
                return device;
            }
        }
        return null;
    }

    private static Set<LightsCell> cells(List<int[]> raw) {
        Set<LightsCell> out = new HashSet<>();
        for (int[] xyz : raw) {
            LightsCell c = cell(xyz);
            if (c != null) {
                out.add(c);
            }
        }
        return out;
    }

    private static LightsCell cell(int[] xyz) {
        return xyz == null || xyz.length != 3 ? null : new LightsCell(xyz[0], xyz[1], xyz[2]);
    }
}
