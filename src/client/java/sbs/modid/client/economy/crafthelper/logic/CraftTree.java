/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.crafthelper.logic;

import sbs.modid.client.economy.crafthelper.model.CraftNode;
import sbs.modid.client.economy.recipe.logic.SkyBlockRepoRecipeProvider;
import sbs.modid.client.economy.recipe.model.ItemRef;
import sbs.modid.client.economy.recipe.model.SbsRecipe;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Expands an item into its full recipe tree with have / need per ingredient, down to base materials.
 *
 * <p><b>The maths.</b> A node needs {@code need}; the player's counts cover {@code have} of it; the
 * rest ({@code missing}) is crafted, so the recipe runs {@code ceil(missing / output count)} times and
 * each ingredient's need is that many runs times its count. A node already fully held is not expanded
 * at all - its sub-tree is not needed, which is the point of the helper.
 *
 * <p><b>Allocation is top-down and shared.</b> One pool of counts is consumed in tree order (parent
 * before children, siblings left to right), so an item held once is never counted for two rows: the
 * Enchanted Flint a parent took is gone before a sibling branch asks for it.
 *
 * <p><b>Stops.</b> Base items (no usable recipe), an item already on the path above (data does carry
 * cycles: A crafts from B, B un-crafts into A), the configured depth, and a node cap that bounds a
 * pathological tree.
 *
 * <p><b>Recipe choice.</b> Candidates are ordered once per item - cheapest first when a cost function
 * is given and can price them, otherwise by source (scraped menus, the recipe database, vanilla, then
 * coin trades) - and the player's pick, keyed by item id, overrides index 0. The same pick applies
 * wherever that item appears in the tree.
 *
 * <p>Pure: no game state, no I/O. Production wiring lives in {@link CraftHelper}.
 */
public final class CraftTree {

    /** Hard cap on nodes in one tree; deep trees of high-count recipes stop here. */
    public static final int MAX_NODES = 2000;

    /** The pseudo-id coin trades use for their price. Never expanded and never counted as held. */
    private static final String COINS = "COINS";

    /**
     * Everything a build reads.
     *
     * @param recipes   recipes producing an id (lookup id, upper case)
     * @param available counts the player holds per id; not modified
     * @param choices   the player's recipe pick per id (index into the ordered candidates)
     * @param maxDepth  deepest level expanded; the root is depth 0
     * @param unitCost  cost of one unit produced by a recipe, or {@code null} for none / unpriceable;
     *                  the whole function may be {@code null} to order by source only
     */
    public record Inputs(Function<String, List<SbsRecipe>> recipes, Map<String, Long> available,
                         Map<String, Integer> choices, int maxDepth, Function<SbsRecipe, Long> unitCost) {
    }

    private final Inputs inputs;
    private final Map<String, Long> pool;
    private final Map<String, List<SbsRecipe>> ordered = new HashMap<>();
    private final Deque<String> path = new ArrayDeque<>();
    private int nodes;

    private CraftTree(Inputs inputs) {
        this.inputs = inputs;
        this.pool = new HashMap<>();
        for (Map.Entry<String, Long> entry : inputs.available().entrySet()) {
            pool.put(normalise(entry.getKey()), entry.getValue());
        }
    }

    /** Builds the tree for {@code quantity} of {@code id}. */
    public static CraftNode build(String id, String name, long quantity, Inputs inputs) {
        return new CraftTree(inputs).expand(normalise(id), name, Math.max(1, quantity), "0", 0);
    }

    /** The usable recipes for {@code id}, in the order {@link #build} numbers them. */
    public static List<SbsRecipe> candidates(String id, Inputs inputs) {
        return new CraftTree(inputs).ordered(normalise(id));
    }

    private CraftNode expand(String id, String name, long need, String key, int depth) {
        nodes++;
        long have = 0;
        if (!COINS.equals(id)) {
            long held = pool.getOrDefault(id, 0L);
            have = Math.min(held, need);
            if (have > 0) {
                pool.put(id, held - have);
            }
        }
        List<SbsRecipe> recipes = COINS.equals(id) ? List.of() : ordered(id);
        int count = recipes.size();
        if (count == 0) {
            return new CraftNode(id, name, key, need, have, -1, 0, CraftNode.Stop.BASE, List.of());
        }
        int index = Math.floorMod(inputs.choices().getOrDefault(id, 0), count);
        long missing = need - have;
        CraftNode.Stop stop = null;
        if (missing <= 0) {
            stop = CraftNode.Stop.SATISFIED;
        } else if (path.contains(id)) {
            stop = CraftNode.Stop.CYCLE;
        } else if (depth >= inputs.maxDepth()) {
            stop = CraftNode.Stop.DEPTH;
        } else if (nodes >= MAX_NODES) {
            stop = CraftNode.Stop.LIMIT;
        }
        if (stop != null) {
            return new CraftNode(id, name, key, need, have, index, count, stop, List.of());
        }
        SbsRecipe recipe = recipes.get(index);
        long runs = ceilDiv(missing, outputCount(recipe));
        List<CraftNode> children = new ArrayList<>(recipe.ingredients.size());
        path.push(id);
        try {
            int childIndex = 0;
            for (ItemRef ingredient : recipe.ingredients) {
                String childId = normalise(ingredient.lookupId());
                long childNeed = saturatingMultiply(runs, Math.max(1, ingredient.count));
                children.add(expand(childId, displayName(ingredient), childNeed,
                        key + "/" + childIndex++, depth + 1));
            }
        } finally {
            path.pop();
        }
        return new CraftNode(id, name, key, need, have, index, count, CraftNode.Stop.NONE, children);
    }

    /** Usable recipes for {@code id}, ordered and cached for this build. */
    private List<SbsRecipe> ordered(String id) {
        List<SbsRecipe> cached = ordered.get(id);
        if (cached != null) {
            return cached;
        }
        List<SbsRecipe> usable = new ArrayList<>();
        List<SbsRecipe> raw = inputs.recipes().apply(id);
        if (raw != null) {
            for (SbsRecipe recipe : raw) {
                if (usable(recipe, id)) {
                    usable.add(recipe);
                }
            }
        }
        Map<SbsRecipe, Long> costs = new HashMap<>();
        if (inputs.unitCost() != null && usable.size() > 1) {
            for (SbsRecipe recipe : usable) {
                Long cost = inputs.unitCost().apply(recipe);
                if (cost != null) {
                    costs.put(recipe, cost);
                }
            }
        }
        // Stable: ties keep the provider's own order.
        usable.sort(Comparator.<SbsRecipe>comparingLong(r -> costs.getOrDefault(r, Long.MAX_VALUE))
                .thenComparingInt(CraftTree::sourceRank));
        List<SbsRecipe> result = List.copyOf(usable);
        ordered.put(id, result);
        return result;
    }

    /** A recipe that produces {@code id} from at least one real ingredient, and not from itself alone. */
    private static boolean usable(SbsRecipe recipe, String id) {
        if (recipe == null || recipe.result == null || recipe.ingredients == null || recipe.ingredients.isEmpty()) {
            return false;
        }
        boolean other = false;
        for (ItemRef ingredient : recipe.ingredients) {
            if (ingredient == null || ingredient.lookupId().isEmpty()) {
                return false;
            }
            if (!normalise(ingredient.lookupId()).equals(id)) {
                other = true;
            }
        }
        return other;
    }

    /** Scraped menus first, then the recipe database, vanilla, and coin trades last. */
    static int sourceRank(SbsRecipe recipe) {
        for (ItemRef ingredient : recipe.ingredients) {
            if (COINS.equals(normalise(ingredient.lookupId()))) {
                return 3;
            }
        }
        if ("vanilla".equalsIgnoreCase(recipe.category)) {
            return 2;
        }
        return SkyBlockRepoRecipeProvider.SOURCE_NAME.equals(recipe.source) ? 1 : 0;
    }

    private static int outputCount(SbsRecipe recipe) {
        return recipe.result == null ? 1 : Math.max(1, recipe.result.count);
    }

    private static String displayName(ItemRef ref) {
        return ref.name != null && !ref.name.isEmpty() ? ref.name : ref.lookupId();
    }

    static String normalise(String id) {
        return id == null ? "" : id.toUpperCase(Locale.ROOT);
    }

    static long ceilDiv(long value, long divisor) {
        return value <= 0 ? 0 : Math.ceilDiv(value, divisor);
    }

    private static long saturatingMultiply(long a, long b) {
        long high = Math.multiplyHigh(a, b);
        long low = a * b;
        return (high == 0 && low >= 0) ? low : Long.MAX_VALUE;
    }
}
