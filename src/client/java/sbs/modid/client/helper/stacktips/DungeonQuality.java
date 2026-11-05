/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.stacktips;

import net.minecraft.nbt.CompoundTag;

/**
 * A dungeon drop's quality roll: {@code baseStatBoostPercentage} (0 to {@link #MAX}) and the
 * {@code item_tier} it dropped on, both ints on the item's SkyBlock attributes. Pure values in, so
 * the text, the percentage and the colour are unit-tested without a game.
 *
 * <p><b>Field names and the maximum are not yet confirmed on this client</b> - see
 * {@code docs/features/item-stack-tips.md}, <i>Dungeon quality</i>. {@link DungeonQualityProbe} logs
 * the real data the first time each item id is seen.
 */
public record DungeonQuality(int boost, int floor) {

    /** The custom-data key of the quality roll. */
    public static final String BOOST_KEY = "baseStatBoostPercentage";
    /** The custom-data key of the floor the item dropped on. */
    public static final String TIER_KEY = "item_tier";
    /** The highest roll; 50 is the commonly reported cap, unconfirmed here. */
    public static final int MAX = 50;

    /** Vanilla §6 gold, §a green, §f white - opaque, as {@code GuiGraphicsExtractor.text} wants. */
    static final int GOLD = 0xFFFFAA00;
    static final int GREEN = 0xFF55FF55;
    static final int WHITE = 0xFFFFFFFF;

    /**
     * The quality of a drop, or {@code null} when it has no roll - which is every non-dungeon item.
     *
     * @param boost the {@code baseStatBoostPercentage} value, negative for absent
     * @param floor the {@code item_tier} value, negative for absent
     */
    public static DungeonQuality of(int boost, int floor) {
        return boost < 0 ? null : new DungeonQuality(Math.min(boost, MAX), floor);
    }

    /** Reads the two ints off a stack's attribute compound; read-only, never copies it. */
    public static DungeonQuality read(CompoundTag extra) {
        return of(extra.getIntOr(BOOST_KEY, -1), extra.getIntOr(TIER_KEY, -1));
    }

    /** The roll as a whole percentage of {@link #MAX}: 48 → 96. */
    public int percent() {
        return Math.round(boost * 100f / MAX);
    }

    /**
     * What the stack tip draws: the percentage digits without the {@code %} sign. "96%" is four
     * glyphs, 23 px, and would run into the next slot; "96" fits beside the vanilla count like every
     * other tip, and the tooltip line spells the sign out.
     */
    public String tipText() {
        return String.valueOf(percent());
    }

    /** Gold at the maximum, green from 80 %, white below. */
    public int color() {
        if (boost >= MAX) {
            return GOLD;
        }
        return percent() >= 80 ? GREEN : WHITE;
    }

    /** "Quality: 48/50 (96%) · Floor 7"; the floor part is left out when the item has none. */
    public String tooltipText() {
        String text = "Quality: " + boost + "/" + MAX + " (" + percent() + "%)";
        return floor >= 0 ? text + " · Floor " + floor : text;
    }
}
