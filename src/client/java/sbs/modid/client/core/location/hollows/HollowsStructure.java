/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.location.hollows;

import java.util.Locale;

/**
 * A Crystal Hollows place that has a zone line of its own, and so can be found by walking into it.
 * The one list of them: the SkyBlock Map's Hollows layer, the schematic Crystal Hollows map and
 * Structure Sharing all read it here.
 *
 * <p><b>Two ids, both fixed.</b> The constant name is what {@code ch_map.json} stores; the
 * {@link #wireId()} is what Structure Sharing sends. The wire id is written by hand and never derived
 * from the constant or the zone, so renaming either cannot change what is on the wire. Never rename a
 * constant or change a wire id - add a new constant instead.
 *
 * <p><b>Not every place is shared.</b> The Crystal Nucleus is the fixed centre: worth an icon on the
 * map, but sharing its position tells nobody anything, so it has no wire id and {@link #shared()} is
 * false.
 *
 * <p>The zone strings come from the Crystal Hollows list in {@code core/keybind/IslandCatalog} and
 * are <b>unverified</b> against a captured sidebar. The four quadrants and Magma Fields are in that
 * list too and are absent here on purpose: they are regions, see {@link HollowsRegion}.
 */
public enum HollowsStructure {

    JUNGLE_TEMPLE("Jungle Temple", "T", "jungle_temple", HollowsRegion.JUNGLE),
    MINES_OF_DIVAN("Mines of Divan", "D", "mines_of_divan", HollowsRegion.MITHRIL_DEPOSITS),
    GOBLIN_QUEENS_DEN("Goblin Queen's Den", "Q", "goblin_queens_den", HollowsRegion.GOBLIN_HOLDOUT),
    LOST_PRECURSOR_CITY("Lost Precursor City", "C", "lost_precursor_city",
            HollowsRegion.PRECURSOR_REMNANTS),
    KHAZAD_DUM("Khazad-dûm", "K", "khazad_dum", HollowsRegion.MAGMA_FIELDS),
    /** Its region is unknown, so it is plausible anywhere inside the bounds. */
    FAIRY_GROTTO("Fairy Grotto", "F", "fairy_grotto", null),
    /** Its region is unknown, so it is plausible anywhere inside the bounds. */
    DRAGONS_LAIR("Dragon's Lair", "L", "dragons_lair", null),
    /** The fixed centre: drawn, never shared. */
    CRYSTAL_NUCLEUS("Crystal Nucleus", "N", null, HollowsRegion.CRYSTAL_NUCLEUS);

    private final String zone;
    private final String glyph;
    private final String wireId;
    private final HollowsRegion region;

    HollowsStructure(String zone, String glyph, String wireId, HollowsRegion region) {
        this.zone = zone;
        this.glyph = glyph;
        this.wireId = wireId;
        this.region = region;
    }

    /** The zone line text, which is also the name shown to the player. */
    public String zone() {
        return zone;
    }

    public String displayName() {
        return zone;
    }

    /** One ASCII letter drawn inside the map icon. */
    public String glyph() {
        return glyph;
    }

    /** The stable protocol id ({@code mines_of_divan}); {@code null} for a place that is not shared. */
    public String wireId() {
        return wireId;
    }

    /** Whether Structure Sharing reports and accepts this place. */
    public boolean shared() {
        return wireId != null;
    }

    /** The region this structure is expected in, or {@code null} when that is unknown. */
    public HollowsRegion region() {
        return region;
    }

    /** Whether a position is inside the Hollows and in this structure's expected region. */
    public boolean plausibleAt(int x, int y, int z) {
        return HollowsGeometry.inBounds(x, y, z) && HollowsGeometry.plausible(region, x, y, z);
    }

    /**
     * The structure a zone line names, or {@code null}. Exact match after trimming, ignoring case: a
     * near-miss is not a structure, because a marker under the wrong name states something false in
     * the same shape as everything true on the map.
     */
    public static HollowsStructure fromZone(String zone) {
        if (zone == null) {
            return null;
        }
        String wanted = zone.trim().toLowerCase(Locale.ROOT);
        for (HollowsStructure structure : values()) {
            if (structure.zone.toLowerCase(Locale.ROOT).equals(wanted)) {
                return structure;
            }
        }
        return null;
    }

    /** The constant for a stored id, or {@code null} for an id this build does not know. */
    public static HollowsStructure fromId(String id) {
        if (id == null) {
            return null;
        }
        for (HollowsStructure structure : values()) {
            if (structure.name().equals(id)) {
                return structure;
            }
        }
        return null;
    }

    /** The shared structure a wire id names, or {@code null} for an id this build does not share. */
    public static HollowsStructure byWireId(String id) {
        if (id == null) {
            return null;
        }
        String wanted = id.trim().toLowerCase(Locale.ROOT);
        for (HollowsStructure structure : values()) {
            if (wanted.equals(structure.wireId)) {
                return structure;
            }
        }
        return null;
    }
}
