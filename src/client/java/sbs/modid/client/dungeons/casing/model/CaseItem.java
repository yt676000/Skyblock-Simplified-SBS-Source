/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.casing.model;

import sbs.modid.client.core.item.Rarity;

/**
 * One cell of the case-opening reel: a SkyBlock item id and its rarity.
 *
 * <p>The rarity drives two things and touches nothing on the server: how often the item shows up as
 * filler on the reel (common items appear more, so the strip <i>looks</i> like the real odds), and
 * how loud the landing effect is (a MYTHIC win gets fireworks, a COMMON one a quiet highlight).
 */
public record CaseItem(String id, Rarity rarity) {

    public CaseItem {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("case item needs an id");
        }
        if (rarity == null) {
            rarity = Rarity.COMMON;
        }
    }

    /**
     * How often this rarity appears as filler on the reel – common items frequent, mythics rare.
     * This is purely cosmetic weighting of the <i>display</i> strip; it never influences what the
     * player actually receives, which is fixed by the server before the animation even starts.
     */
    public int reelWeight() {
        return switch (rarity) {
            case COMMON -> 100;
            case UNCOMMON -> 60;
            case RARE -> 30;
            case EPIC -> 12;
            case LEGENDARY -> 4;
            // Above legendary the item is essentially never filler – it only ever appears as a win.
            case MYTHIC, SPECIAL, DIVINE -> 1;
        };
    }
}
