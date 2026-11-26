/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.wishlist.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One AH Wishlist entry: which item the player wants, the most they will pay, and the spec it must
 * have. Stored in the config through Gson, so every field is a plain public field with a default.
 *
 * <p><b>Every criterion is a minimum.</b> An item with a higher enchant level, more stars, more
 * potato books, more unlocked gem slots or better gems than asked for still matches; extra enchants
 * and extra gems never disqualify. The only way to exclude something is {@link #blacklist}.
 */
public final class WishlistEntry {

    /** Stable id of the entry, assigned once. Not shown; used to tell two entries apart. */
    public String id = UUID.randomUUID().toString();

    /** Whether the entry is watched. */
    public boolean enabled = true;

    /** The item's display name as the player typed it ("Necron's Chestplate"). Matched by name key. */
    public String itemName = "";

    /**
     * The SkyBlock id ({@code POWER_WITHER_CHESTPLATE}), when the entry was made from a real item.
     * Empty when typed by hand; then the name alone decides which item it is.
     */
    public String itemId = "";

    /** Most the player will pay, in coins. {@code 0} means no limit. */
    public long maxPrice = 0;

    /** Enchant key (lower case, as in the item's {@code enchantments}) to minimum level. */
    public Map<String, Integer> enchants = new LinkedHashMap<>();

    /** Required reforge ({@code modifier}, lower case); empty = any. */
    public String reforge = "";

    /** Minimum stars (dungeon/upgrade level, master stars included). */
    public int minStars = 0;

    /** Whether the item must be recombobulated. */
    public boolean recomb = false;

    /** Minimum hot potato books; 11 to 15 means fuming books on top of the ten hot ones. */
    public int minPotatoBooks = 0;

    /** Minimum number of open gemstone slots (unlocked or holding a gem). */
    public int minGemSlots = 0;

    /** Gemstones that must be applied. */
    public List<GemRequirement> gems = new ArrayList<>();

    /**
     * Exclusions, one token each: {@code skin}, {@code skin:<id>}, {@code dye}, {@code dye:<id>},
     * {@code reforge:<name>}, {@code enchant:<key>}, {@code gem:<type>}. Lower case.
     */
    public List<String> blacklist = new ArrayList<>();

    /** One gemstone requirement: {@code count} gems of {@code type} at {@code minQuality} or better. */
    public static final class GemRequirement {

        /** Gem type ({@code JASPER}), or {@code ANY}. */
        public String type = "ANY";

        /** Lowest acceptable quality ({@code ROUGH} .. {@code PERFECT}). */
        public String minQuality = "ROUGH";

        public int count = 1;

        public GemRequirement() {
        }

        public GemRequirement(String type, String minQuality, int count) {
            this.type = type;
            this.minQuality = minQuality;
            this.count = count;
        }
    }
}
