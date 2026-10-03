/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.nucleus.logic;

import sbs.modid.client.dungeons.chest.DungeonChestValue;
import sbs.modid.client.skills.mining.logic.GemstoneCatalog;

import java.util.function.Function;

/**
 * A reward line's display name to its SkyBlock id, through the parsers the mod already has.
 *
 * <p>Order: gemstones ({@code Fine Amber Gemstone} to {@code FINE_AMBER_GEM}), then enchanted books
 * and essences ({@code Enchanted Book (Lapidary I)} to {@code ENCHANTMENT_LAPIDARY_1}), then the
 * item catalogue's exact display name. Never the catalogue's normalised-name lookup: it strips
 * {@code Fine} and {@code Perfect} as reforges, which turns one gemstone grade into another.
 * {@code null} means unresolved - the item is kept by name and shown as unpriced.
 */
public final class NucleusItemIds {

    private final Function<String, String> catalogByName;

    /** @param catalogByName exact display name to id, or {@code null} */
    public NucleusItemIds(Function<String, String> catalogByName) {
        this.catalogByName = catalogByName;
    }

    public String resolve(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return null;
        }
        String name = NucleusChatParser.withoutGlyph(displayName.trim());
        GemstoneCatalog.Gem gem = GemstoneCatalog.byDisplayName(name);
        if (gem != null) {
            return gem.bazaarId();
        }
        String shaped = DungeonChestValue.bazaarIdFor(name);
        if (shaped != null) {
            return shaped;
        }
        return catalogByName.apply(name);
    }
}
