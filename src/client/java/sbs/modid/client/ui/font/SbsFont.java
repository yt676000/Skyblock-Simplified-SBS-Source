/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.font;

import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;

/**
 * One selectable font.
 *
 * <p><b>{@code id} is the identity and never changes.</b> It is what the config file stores, so
 * renaming one silently resets the font of everybody who had picked it - the same act as deleting
 * their setting (see the Identity rule in the root {@code AGENTS.md}). Display names are free to
 * change; ids are not.
 *
 * @param id          stable config key, e.g. {@code inter}. Never renamed, never derived from the
 *                    display name
 * @param fontId      the Minecraft font definition this resolves to - a {@code font/<name>.json}
 *                    somewhere in the resource stack
 * @param displayName what the settings row shows
 * @param origin      who supplied the file; see {@link FontOrigin}
 * @param licence     licence facts, for {@link FontOrigin#BUNDLED} only - {@code null} everywhere
 *                    else, because we make no claim about a font we did not ship
 * @param recommendedMinGuiScale advisory only. Pixel fonts have one true size and turn to mush below
 *                    it; the settings page mentions this and nothing enforces it, because a hard
 *                    floor on a cosmetic setting is worse than a blurry glyph
 */
public record SbsFont(String id,
                      Identifier fontId,
                      String displayName,
                      FontOrigin origin,
                      FontLicence licence,
                      int recommendedMinGuiScale) {

    /** True when this font carries licence facts - {@link FontOrigin#BUNDLED} entries only. */
    public boolean hasLicence() {
        return licence != null;
    }

    /** How this font is named in a {@link net.minecraft.network.chat.Style}. */
    public FontDescription description() {
        return new FontDescription.Resource(fontId);
    }

    /** A bundled or user font whose file could be missing; vanilla fonts are always present. */
    public boolean mayBeMissing() {
        return origin != FontOrigin.VANILLA;
    }
}
