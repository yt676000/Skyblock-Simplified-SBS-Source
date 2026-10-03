/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.rift.model;

import sbs.modid.client.core.data.VersionedDocument;

import java.util.ArrayList;
import java.util.List;

/**
 * The static Rift data file: what each area does to your clock, and what it wants before it lets you
 * in. Loaded through the shared versioned loader, so a Hypixel change ships as a data bump.
 *
 * <p><b>What "costs time to enter" actually turned out to be.</b> There is no area in the Rift that
 * deducts a lump of ф from you at its door - the mechanic is two other things, and both are modelled
 * here because both are what the player is really asking about:
 * <ul>
 *   <li>a <b>drain multiplier</b>: the Colosseum runs at half speed, the Time Chamber at double, and
 *       the Wizard Tower, Rift Gallery and Mirrorverse do not drain at all;</li>
 *   <li>a <b>minimum</b> you must be holding to be let in at all - the Mirrorverse wants three
 *       minutes on the clock before it opens.</li>
 * </ul>
 * The warning the feature gives is built from those two plus the walk back out, which is the real
 * question behind "can I afford this": not what the door charges, but whether what you have left
 * covers going in, doing the thing, and getting to somewhere that is not a forced teleport.
 */
public final class RiftData implements VersionedDocument {

    public int schemaVersion = 1;
    public int dataVersion;
    public String generatedAt = "";

    public List<Area> areas = new ArrayList<>();

    /** One named place in the Rift and how it treats the clock. */
    public static final class Area {

        /**
         * The zone name as the sidebar spells it ("Colosseum", "Mirrorverse") - matched against
         * {@link sbs.modid.client.core.location.SkyBlockLocation#zone()}.
         */
        public String zone = "";

        /**
         * How fast ф drains here relative to normal: {@code 0} for the areas that do not drain,
         * {@code 0.5} for half, {@code 2} for double. Defaults to normal speed so an area listed only
         * for its entry requirement does not accidentally claim to be free.
         */
        public double drainMultiplier = 1.0;

        /**
         * Seconds of ф the area requires before it will let the player in; {@code 0} for no minimum.
         *
         * <p>Its own field rather than folded into a generic "cost" because it is <b>not</b> spent -
         * the Mirrorverse wants you to be holding three minutes, it does not take them. A player
         * warned that something costs 180ф when it costs nothing would start hoarding for no reason.
         */
        public int minimumSeconds;

        /**
         * Roughly how long it takes to walk from here back to somewhere that does not drain, in
         * seconds. What the "you cannot afford to leave" warning is measured against.
         *
         * <p>A hand-entered estimate, and honest about it: routing it properly would need the Rift's
         * navmesh, and a number that is roughly right turns the warning on at roughly the right time,
         * which is all a warning has to do. {@code 0} means the area is not far enough in to warn about.
         */
        public int exitSeconds;

        /** Optional note shown with the warning ("time freezes inside"). */
        public String note = "";

        /**
         * How much this entry is worth trusting. Everything in the bundled file is {@link
         * Certainty#WIKI} until somebody has watched the clock do it, and the card says so.
         */
        public Certainty certainty = Certainty.WIKI;

        public Area() {
        }

        public boolean valid() {
            return zone != null && !zone.isBlank();
        }

        /** Whether the clock stands still here. */
        public boolean frozen() {
            return drainMultiplier <= 0.0;
        }
    }

    public RiftData() {
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
        return areas != null && !areas.isEmpty();
    }

    /** Drops unusable entries. Called once when a document becomes live. */
    public void link() {
        if (areas == null) {
            areas = new ArrayList<>();
            return;
        }
        areas.removeIf(area -> area == null || !area.valid());
    }
}
