/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.model;

import java.util.Locale;

/**
 * The twelve gemstone kinds, each with the colour SkyBlock draws it in.
 *
 * <p>All twelve are Bazaar-traded at all five grades - sixty products, confirmed present in the live
 * snapshot - so nothing here needs a "is this one tradeable" special case. The five that the Crystal
 * Nucleus uses (Jade, Amber, Amethyst, Sapphire, Topaz) are not distinguished: for a profit figure a
 * gemstone is a gemstone, and which ones the player happens to be mining is an observation, not a
 * configuration.
 */
public enum GemstoneType {

    JADE("Jade", 0xFF57D977),
    AMBER("Amber", 0xFFE8A33D),
    AMETHYST("Amethyst", 0xFFB45FD6),
    SAPPHIRE("Sapphire", 0xFF4FC3F7),
    TOPAZ("Topaz", 0xFFF2E15C),
    JASPER("Jasper", 0xFFF0567A),
    RUBY("Ruby", 0xFFE8453D),
    OPAL("Opal", 0xFFE8E8E8),
    ONYX("Onyx", 0xFF6B5B8C),
    CITRINE("Citrine", 0xFFC97B3D),
    AQUAMARINE("Aquamarine", 0xFF6BD9E8),
    PERIDOT("Peridot", 0xFF9BD94F);

    private final String displayName;
    private final int color;

    GemstoneType(String displayName, int color) {
        this.displayName = displayName;
        this.color = color;
    }

    public String displayName() {
        return displayName;
    }

    /** ARGB the gemstone is drawn in, so a row is identifiable without reading it. */
    public int color() {
        return color;
    }

    /** The Bazaar id part between the tier and {@code _GEM}. */
    public String idPart() {
        return name();
    }

    /** The type whose display name matches, or {@code null}. Case-insensitive. */
    public static GemstoneType byName(String name) {
        if (name == null) {
            return null;
        }
        String wanted = name.trim().toLowerCase(Locale.ROOT);
        for (GemstoneType type : values()) {
            if (type.displayName.toLowerCase(Locale.ROOT).equals(wanted)) {
                return type;
            }
        }
        return null;
    }
}
