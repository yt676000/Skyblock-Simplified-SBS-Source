/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.recipe.model;

import java.util.ArrayList;
import java.util.List;

/**
 * One Forge recipe: what goes in, what comes out, and <b>how long it takes</b>.
 *
 * <p>The duration is the field that makes this worth having as its own model rather than folding
 * forge into {@link SbsRecipe}. Every other recipe in the mod is instantaneous, so "what is this
 * worth" is a subtraction; a forge recipe occupies a slot for anywhere from 30 seconds to 8 hours,
 * and the only figure that compares two of them is profit <i>per forge hour</i>. A recipe model with
 * no time in it cannot answer the question the feature exists to answer.
 *
 * <p>Hypixel publishes no recipe endpoint, so these are read out of the same public SkyBlock item-data
 * archive {@link sbs.modid.client.economy.recipe.logic.SkyBlockRepoRecipeProvider} already downloads
 * for the Recipe Viewer — one pass, one cache, no second download.
 *
 * <p>A plain class with public fields rather than a record, because instances are read straight back
 * out of a Gson cache file and the surrounding provider models ({@code Appearance}, {@code SbsRecipe})
 * are written the same way.
 */
public final class ForgeRecipe {

    /** SkyBlock id of the item the forge produces. */
    public String outputId;

    /** Display name from the archive, § codes stripped; falls back to the id. */
    public String displayName;

    /** How many are produced per run. Fractional in the source data, so not an {@code int}. */
    public double outputCount = 1;

    /** Forge time for one run, in <b>seconds</b> (REFINED_DIAMOND = 28800 = 8h). */
    public int durationSeconds;

    /**
     * The archive's free-text unlock note ({@code crafttext}), e.g. a Heart of the Mountain tier.
     * Shown verbatim and never parsed — it is prose, and a recipe the player cannot use yet is worth
     * saying so about even when we cannot tell exactly what it needs.
     */
    public String requirement = "";

    /** What one run consumes. Never empty for a stored recipe. */
    public List<Ingredient> inputs = new ArrayList<>();

    /** One input line: a SkyBlock id and how many of it one run consumes. */
    public static final class Ingredient {
        public String itemId;
        public double count = 1;

        public Ingredient() {
        }

        public Ingredient(String itemId, double count) {
            this.itemId = itemId;
            this.count = count;
        }
    }

    /** True when this entry survived the cache round trip with everything the engine needs. */
    public boolean usable() {
        if (outputId == null || outputId.isBlank() || durationSeconds <= 0 || outputCount <= 0
                || inputs == null || inputs.isEmpty()) {
            return false;
        }
        for (Ingredient input : inputs) {
            if (input == null || input.itemId == null || input.itemId.isBlank() || input.count <= 0) {
                return false;
            }
        }
        return true;
    }

    /** The name to show, never blank. */
    public String label() {
        return displayName == null || displayName.isBlank() ? outputId : displayName;
    }
}
