/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.build.model;

import java.util.List;
import java.util.Map;

/**
 * What an applied edit changed, enough to take it back or do it again: for every position, the
 * state before and after, as indices into one palette, plus block-entity data on either side.
 *
 * <p>Packed arrays rather than objects per block - a 4M-block edit is 4M longs and 8M chars, not 4M
 * records - which is what lets the timeline hold several large edits in memory at once.
 *
 * @param positions      packed positions ({@link EditShapes#pack}), in the order they were applied
 * @param before         palette index of each position's state before the edit
 * @param after          palette index after it
 * @param palette        state strings, [0] = air
 * @param beforeEntities block-entity SNBT before the edit, by position index
 * @param afterEntities  block-entity SNBT after it, by position index
 * @param changed        how many positions actually changed state
 */
public record EditRecord(long[] positions, char[] before, char[] after, List<String> palette,
                         Map<Integer, String> beforeEntities, Map<Integer, String> afterEntities,
                         int changed) implements EditHistory.Sized {

    @Override
    public long cells() {
        return positions.length;
    }
}
