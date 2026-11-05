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
 * Known wirings of the phase-3 Lights device ({@code dungeons/f7_lights_wiring.json}).
 *
 * <p><b>Ships empty on purpose.</b> Every entry is to come from our own {@code [SBS][Lights]} probe
 * captures, never from anywhere else, and none exist yet. Until one does, the device's wiring is
 * learned live in each run.
 *
 * <p>Coordinates are relative to the layout anchor - the minimum corner over every lever and lamp -
 * exactly as the probe logs them. {@code toggles.get(i)} lists the indices into {@code lamps} that
 * lever {@code levers.get(i)} toggles. The file describes data only: it cannot name a block to click
 * or anything the client acts on.
 */
public final class LightsWiringData implements VersionedDocument {

    public int schemaVersion = 1;
    public int dataVersion;
    public String generatedAt = "";

    public List<Device> devices = new ArrayList<>();

    /** One captured build. */
    public static final class Device {
        /** Stable id, e.g. {@code f7_p3_lights_s2}. Never renumbered. */
        public String id = "";
        /** CONFIRMED once a capture has been replayed against a completed device. */
        public Certainty certainty = Certainty.UNKNOWN;
        /** Date of the capture, {@code YYYY-MM-DD}. */
        public String capturedOn = "";
        /** Which capture it came from (log date / session), for re-checking. */
        public String source = "";
        /** Relative lever positions, each {@code [x, y, z]}. */
        public List<int[]> levers = new ArrayList<>();
        /** Relative lamp positions, each {@code [x, y, z]}. */
        public List<int[]> lamps = new ArrayList<>();
        /** Per lever, the lamp indices it toggles. */
        public List<List<Integer>> toggles = new ArrayList<>();
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
        return devices != null;
    }
}
