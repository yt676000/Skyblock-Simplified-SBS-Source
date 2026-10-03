/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.font;

/**
 * Where a registered font came from. The distinction is not cosmetic: it decides what we are allowed
 * to say about the font, and what we are allowed to do with the file.
 *
 * <ul>
 *   <li>{@link #VANILLA} - shipped by Minecraft. We distribute nothing and the font is present on
 *       every install, so these are the only entries guaranteed to resolve.
 *   <li>{@link #BUNDLED} - shipped inside our jar. Every one carries a {@link FontLicence}, and the
 *       licence text travels with it in the repository's {@code LICENSES/} directory.
 *   <li>{@link #USER} - a {@code .ttf} the player dropped into {@code config/sbs/fonts}. We never
 *       copy it, never re-emit it, and make <b>no</b> claim about its licence - see
 *       {@link SbsFont#licence()}.
 * </ul>
 */
public enum FontOrigin {

    /** Ships with Minecraft itself. */
    VANILLA("Minecraft"),

    /** Ships inside the SBS jar under a licence we have checked. */
    BUNDLED("Bundled"),

    /** Supplied by the player from the config folder. */
    USER("Yours");

    private final String displayName;

    FontOrigin(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
