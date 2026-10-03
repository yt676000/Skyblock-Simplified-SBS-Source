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
 * One recipe in the SBS Recipe Viewer, independent of where it came from (vanilla, SkyBlock crafting,
 * Forge, NPC, ...). A plain Gson-serializable POJO so scraped recipes persist to disk unchanged.
 *
 * <p>{@code grid} is an optional 9-slot (3x3) shaped-crafting layout ({@code null} entries = empty
 * slots); {@code ingredients} is the aggregated list (always present) used for the amounts summary.
 * {@code category} is the provider bucket ("Crafting", "Forge", "NPC", ...) and {@code source} a
 * human-readable origin note (e.g. the menu title the recipe was captured from).
 */
public final class SbsRecipe {

    public String category = "Crafting";
    public String source = "";
    public ItemRef result;
    public List<ItemRef> ingredients = new ArrayList<>();

    /** Optional 3x3 shaped layout, row-major; entries may be {@code null} for empty grid slots. */
    public ItemRef[] grid;

    public SbsRecipe() {
    }

    public SbsRecipe(String category, String source, ItemRef result) {
        this.category = category;
        this.source = source;
        this.result = result;
    }

    /** Stable key used for de-duplication in the store (result id + category). */
    public String key() {
        return (result != null ? result.lookupId() : "?") + "|" + category;
    }
}
