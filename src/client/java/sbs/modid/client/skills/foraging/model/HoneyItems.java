/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.foraging.model;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.item.SkyblockItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * "Is the player holding Honeycomb" - the one item question the honey tree timer asks.
 *
 * <p><b>The id is not confirmed, and this class is shaped by that.</b> {@code HONEYCOMB} appears in
 * no dataset this repository holds: {@code sbs-skyblock-items.json} has {@code CANDYCOMB},
 * {@code HONEY_JAR} and {@code PET_SKIN_BEE_HONEY}, and nothing else honey-shaped. Root
 * {@code AGENTS.md} is explicit about what that means - a name in a request is a hypothesis, so it
 * becomes a config field with a tolerant match rather than a literal in code, and the feature logs
 * what it actually sees.
 *
 * <p>Three questions are asked, in cost order, and any one of them answering yes is enough:
 *
 * <ol>
 *   <li>the <b>SkyBlock id</b> containing one of the player's configured needles;</li>
 *   <li>the <b>vanilla item</b>, since {@code minecraft:honeycomb} exists and Hypixel builds its
 *       items on vanilla ones - read out of the registry rather than off a class, because which
 *       class an item is has moved between versions;</li>
 *   <li>the <b>display name</b>, which is the one thing that stays readable when Hypixel gives an
 *       item an id nobody guessed.</li>
 * </ol>
 *
 * <p><b>The default needles are deliberately not just {@code COMB}.</b> That substring is in
 * {@code RECOMBOBULATOR_3000}, {@code LARGE_COMBAT_SACK} and every {@code *_COMBAT_TALISMAN} this
 * mod already knows about, so it would arm the timer on a right-click with a talisman in hand. A
 * needle short enough to catch everything is a needle that catches the wrong thing; the player can
 * still add one, and the log says which needle matched.
 */
public final class HoneyItems {

    /** What {@code honey.honeycombIds} means when the player has not changed it. */
    public static final String DEFAULT_IDS = "HONEYCOMB, HONEY_COMB";

    /** The vanilla item Hypixel would most plausibly build a Honeycomb on. */
    private static final String VANILLA = "honeycomb";

    private HoneyItems() {
    }

    /**
     * Whether {@code stack} is Honeycomb as far as anything here can tell.
     *
     * @param needles the player's comma-separated id list; blank falls back to {@link #DEFAULT_IDS}
     */
    public static boolean isHoneycomb(ItemStack stack, String needles) {
        return matched(stack, needles) != null;
    }

    /**
     * <i>Which</i> test recognised the stack, or {@code null} for none.
     *
     * <p>Returned rather than a boolean because this is the line the log prints and the settings
     * page shows: "matched by id HONEYCOMB" and "matched by name Honeycomb" are different facts
     * about Hypixel, and the second one is how the first gets corrected without a new build.
     */
    public static String matched(ItemStack stack, String needles) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        String skyblockId = SkyblockItem.id(stack);
        if (skyblockId != null && !skyblockId.isEmpty()) {
            String upper = skyblockId.toUpperCase(Locale.ROOT);
            for (String needle : parse(needles)) {
                if (upper.contains(needle)) {
                    return "id " + skyblockId + " (needle " + needle + ")";
                }
            }
        }
        Identifier key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (key != null && key.getPath().equals(VANILLA)) {
            return "vanilla " + key;
        }
        String name = stack.getHoverName().getString();
        if (!name.isEmpty() && name.toLowerCase(Locale.ROOT).replace(" ", "").contains(VANILLA)) {
            return "name " + name;
        }
        return null;
    }

    /**
     * The needle list, upper-cased and de-blanked.
     *
     * <p>Parsed per call rather than cached: this runs on a right-click, not per frame, and a cache
     * would have to be invalidated from the settings screen - a second thing to get wrong for a
     * saving that does not exist.
     */
    public static List<String> parse(String needles) {
        String source = needles == null || needles.isBlank() ? DEFAULT_IDS : needles;
        List<String> out = new ArrayList<>(4);
        for (String part : source.split(",")) {
            String trimmed = part.trim().toUpperCase(Locale.ROOT);
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out.isEmpty() ? List.of(DEFAULT_IDS.split(",\\s*")) : out;
    }
}
