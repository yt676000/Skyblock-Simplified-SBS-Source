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
 * The large areas of the Crystal Hollows: four quadrants, the Nucleus in the middle, and Magma
 * Fields underneath all of them.
 *
 * <p>Where each one lies is {@link HollowsGeometry}'s business, and is ESTIMATED there. This enum
 * only names them and gives each a colour for the schematic map.
 */
public enum HollowsRegion {

    JUNGLE("Jungle", 0x3FA34D),
    MITHRIL_DEPOSITS("Mithril Deposits", 0x4FB3BF),
    GOBLIN_HOLDOUT("Goblin Holdout", 0xC9853A),
    PRECURSOR_REMNANTS("Precursor Remnants", 0x8C7FB8),
    CRYSTAL_NUCLEUS("Crystal Nucleus", 0xD77FE0),
    MAGMA_FIELDS("Magma Fields", 0xC2452D);

    private final String zone;
    private final int rgb;

    HollowsRegion(String zone, int rgb) {
        this.zone = zone;
        this.rgb = rgb;
    }

    /** The zone line text for this region. */
    public String zone() {
        return zone;
    }

    public String displayName() {
        return zone;
    }

    /** Colour as {@code 0xRRGGBB}, no alpha. */
    public int rgb() {
        return rgb;
    }

    /** The region a zone line names, or {@code null} - exact match after trimming, ignoring case. */
    public static HollowsRegion fromZone(String zone) {
        if (zone == null) {
            return null;
        }
        String wanted = zone.trim().toLowerCase(Locale.ROOT);
        for (HollowsRegion region : values()) {
            if (region.zone.toLowerCase(Locale.ROOT).equals(wanted)) {
                return region;
            }
        }
        return null;
    }
}
