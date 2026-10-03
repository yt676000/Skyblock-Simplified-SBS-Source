/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.rift.model;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * One Enigma Soul from the data file.
 *
 * <p><b>{@link #id} is the identity, not the coordinates.</b> Collection state persists against the
 * id, so an id that gets renumbered between data versions silently wipes the player's progress. Ids
 * are therefore assigned <b>once</b>, from a hash of the coordinates the soul was first catalogued
 * at, and never recomputed - correcting a coordinate later must not change the id, because moving a
 * soul by a block does not make it a different soul.
 *
 * <p><b>A coordinate on its own is useless here, and that is the whole difference from a Fairy
 * Soul.</b> Most Enigma Souls are not a right-click on an orb: they are two players on pressure
 * plates, three minutes sitting next to an NPC, fifty hot dogs, a specific item swung at a specific
 * block, or a purchase. A marker that says "it is here" and nothing else sends the player to stare at
 * an empty wall. So {@link #instruction} is not decoration - it is the feature, and the renderer
 * surfaces it on the marker rather than hiding it behind a hover.
 *
 * <p>The structured half of that is {@link #minPlayers} and {@link #requiredItem}, which exist
 * separately from the prose because they are what a filter can act on: a player alone on a private
 * server wants the two-player souls out of the field entirely, and no amount of parsing English gets
 * that reliably.
 *
 * <p>A plain mutable POJO because Gson builds it straight out of the data file.
 */
public final class EnigmaSoul {

    /**
     * Stable, permanent id ("rift-3f19c2"). The key every collection record uses.
     *
     * <p>Generated once as {@code "rift-" + first 6 hex of SHA-1("x,y,z")} over the integer block
     * coordinates at the time of cataloguing, then frozen. The hash is only a way to mint an id
     * without a central counter - once it exists it is opaque, and nothing may re-derive it.
     */
    public String id = "";

    /** The soul's own name, as the wiki and the community call it ("Two Plates"). For labels. */
    public String name = "";

    public int x;
    public int y;
    public int z;

    /**
     * The zone the soul sits in, as the sidebar spells it ("Wyld Woods", "Stillgore Château").
     * Display and grouping only - every soul is on the one island.
     */
    public String area = "";

    /**
     * The Rift Guide's own heading for this soul, when it differs from {@link #area}.
     *
     * <p>Needed because the guide files several souls under a location they are not physically in -
     * it groups by the quest chain, not by geography. Without this the guide's per-location counts
     * cannot be matched to the souls they are counting.
     */
    public String guideSection = "";

    /**
     * <b>How to actually get it.</b> One or two sentences, imperative, written for somebody standing
     * at the coordinate wondering why nothing is there. Never blank for a soul that is not a plain
     * pickup, and the renderer shows it on the marker.
     */
    public String instruction = "";

    /**
     * How many players must be present, including the reader. {@code 1} for everything you can do
     * alone, which is the default and the majority.
     *
     * <p>The one condition worth filtering on above all others: a soul that needs a second player is
     * not "hard", it is <b>impossible</b> for somebody playing alone, and leaving it in the field
     * means a permanently unreachable marker that never goes away.
     */
    public int minPlayers = 1;

    /**
     * The item the soul cannot be collected without ("Larva Silk", "Horsezooka", "Berberis
     * Blowgun"), or blank when it needs none. Display and filtering; not matched against the
     * inventory, because the item names on this list are not all things the client can see you own.
     */
    public String requiredItem = "";

    /**
     * What kind of thing this is, for the filter and for the marker's icon-less shorthand. See
     * {@link SoulRequirement}.
     */
    public SoulRequirement requirement = SoulRequirement.PICKUP;

    /**
     * A soul that must be done before this one is possible, by {@link #id}, or blank.
     *
     * <p>Only for a genuine hard dependency (the Mirrorverse being finished, an NPC's chain being
     * exhausted), not for "it is easier afterwards". A wrong entry here hides a soul the player can
     * actually collect, so blank is the correct value whenever there is any doubt.
     */
    public String requiresSoul = "";

    /**
     * The string Hypixel's own API uses for this soul in {@code rift.enigma.found_souls}, when it is
     * known.
     *
     * <p>This is the field that turns per-soul state from inference into fact: the API publishes the
     * <i>names</i> of the souls found, not just how many, so a soul with this filled in can be known
     * exactly rather than guessed at. Blank is fine and normal - the mapping has to be built by
     * comparing a real profile against this file, one soul at a time, and everything works without it
     * (just less precisely).
     */
    public String apiName = "";

    /** How much this entry is worth trusting. Coordinates from a wiki start at {@link Certainty#WIKI}. */
    public Certainty certainty = Certainty.WIKI;

    /** Gson needs a no-arg constructor. */
    public EnigmaSoul() {
    }

    public BlockPos pos() {
        return new BlockPos(x, y, z);
    }

    /** The soul's centre - what distances and the marker are measured against. */
    public Vec3 centre() {
        return new Vec3(x + 0.5, y + 0.5, z + 0.5);
    }

    /** Whether the entry is complete enough to use: an id-less soul cannot be tracked. */
    public boolean valid() {
        return id != null && !id.isBlank();
    }

    /** What to call it on a marker - the name if it has one, else the area, else the id. */
    public String label() {
        if (name != null && !name.isBlank()) {
            return name;
        }
        return area != null && !area.isBlank() ? area : id;
    }

    /** Whether a player on their own can collect this at all. */
    public boolean soloable() {
        return minPlayers <= 1;
    }

    /** "rift-3f19c2 Two Plates (-128, 72, 77)" for chat and logs. */
    public String summary() {
        return id + " " + label() + " (" + x + ", " + y + ", " + z + ")";
    }
}
