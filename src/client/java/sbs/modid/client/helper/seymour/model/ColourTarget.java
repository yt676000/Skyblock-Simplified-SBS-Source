/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.seymour.model;

import sbs.modid.client.helper.rift.model.Certainty;
import sbs.modid.client.helper.seymour.logic.SeymourColour;

/**
 * A colour a piece can match: another armor piece's fixed colour or a dye's.
 *
 * @param name      what the player reads ("Superior Dragon Chestplate")
 * @param itemId    the SkyBlock id it came from
 * @param rgb       packed {@code 0xRRGGBB}
 * @param kind      armor piece or dye
 * @param certainty how far the colour has been checked; see {@link Certainty}
 * @param source    where the colour was read, for the tooltip and the spec
 * @param lab       {@code rgb} in Lab, computed once
 */
public record ColourTarget(String name, String itemId, int rgb, Kind kind, Certainty certainty, String source,
                           SeymourColour.Lab lab) {

    public enum Kind { ARMOR, DYE }

    public static ColourTarget of(String name, String itemId, int rgb, Kind kind, Certainty certainty,
                                  String source) {
        return new ColourTarget(name, itemId, rgb & 0xFFFFFF, kind, certainty, source,
                SeymourColour.toLab(rgb & 0xFFFFFF));
    }
}
