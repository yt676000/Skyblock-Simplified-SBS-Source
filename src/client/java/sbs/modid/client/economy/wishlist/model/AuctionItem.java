/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.wishlist.model;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What an auctioned item actually carries, read once out of its NBT into plain data so the matcher
 * never touches a tag. Strings are normalised: enchant keys, reforge, dye and skin lower case; gem
 * types and qualities upper case.
 *
 * @param itemId         the SkyBlock id, empty when the item has none
 * @param enchants       enchant key to level
 * @param reforge        the {@code modifier}, empty when unreforged
 * @param stars          stars including master stars
 * @param recombobulated whether {@code rarity_upgrades} is set
 * @param potatoBooks    {@code hot_potato_count} (hot and fuming together)
 * @param unlockedSlots  gem slot keys listed as unlocked
 * @param gems           every applied gem
 * @param dye            applied dye id, empty when none
 * @param skin           applied skin id, empty when none
 */
public record AuctionItem(String itemId, Map<String, Integer> enchants, String reforge, int stars,
                          boolean recombobulated, int potatoBooks, Set<String> unlockedSlots,
                          List<Gem> gems, String dye, String skin) {

    /** One applied gem: the slot it sits in, its type and its quality. */
    public record Gem(String slot, String type, String quality) {
    }
}
