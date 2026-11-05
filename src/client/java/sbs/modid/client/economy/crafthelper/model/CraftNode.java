/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.crafthelper.model;

import java.util.List;

/**
 * One row of a Craft Helper tree: an item, how many of it the parent needs, and how many of those the
 * player already holds after everything higher in the tree took its share.
 *
 * <p>Immutable, and free of game classes, so a whole tree can be built and asserted on in a unit test.
 *
 * @param id           the item's lookup id (SkyBlock id, or an upper-cased vanilla material)
 * @param name         display name as the recipe data spells it
 * @param path         stable key of this position in the tree ({@code parent path/child index}); what
 *                     the collapse state is keyed on, so two rows for one item stay independent
 * @param need         how many the parent needs, rounded up to whole crafts
 * @param have         how many of {@code need} the selected sources cover ({@code <= need})
 * @param recipeIndex  which of {@link #recipeCount} recipes was expanded, {@code -1} when none was
 * @param recipeCount  how many usable recipes make this item; above one, the player may switch
 * @param stop         why the tree does not go deeper here
 * @param children     the chosen recipe's ingredients for the amount still missing
 */
public record CraftNode(String id, String name, String path, long need, long have,
                        int recipeIndex, int recipeCount, Stop stop, List<CraftNode> children) {

    /** Why a node has no (further) children. */
    public enum Stop {
        /** Expanded normally; its children are its recipe's ingredients. */
        NONE,
        /** No recipe produces it: a base material. */
        BASE,
        /** The player already holds every one needed, so its recipe is not needed. */
        SATISFIED,
        /** The item already appears on the path above - expanding it again would never end. */
        CYCLE,
        /** The configured maximum depth was reached. */
        DEPTH,
        /** The tree reached its node cap; the rest is left out rather than built without bound. */
        LIMIT
    }

    public CraftNode {
        children = List.copyOf(children);
    }

    /** Whether the held amount covers the need on its own - the row's tick. */
    public boolean done() {
        return have >= need;
    }

    /** How many are still missing. */
    public long missing() {
        return Math.max(0, need - have);
    }

    /**
     * Whether this whole branch is taken care of: either held outright, or every ingredient of the
     * missing part is itself covered. A branch like that is what "show completed branches collapsed"
     * folds away.
     */
    public boolean covered() {
        if (done()) {
            return true;
        }
        if (children.isEmpty()) {
            return false;
        }
        for (CraftNode child : children) {
            if (!child.covered()) {
                return false;
            }
        }
        return true;
    }

    /** The child at {@code index}, for tests and lookups. */
    public CraftNode child(int index) {
        return children.get(index);
    }
}
