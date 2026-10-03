/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.recipe.logic;

import java.util.List;
import sbs.modid.client.economy.recipe.model.SbsRecipe;

/**
 * One source of recipes for the Recipe Viewer.
 *
 * <p>The provider-based architecture keeps recipe types modular: the registry aggregates every
 * registered provider, so adding Forge / Kat / NPC / future SkyBlock recipe types later means adding
 * one provider class and one {@code register(...)} call – no changes to the UI or the registry.
 */
public interface RecipeProvider {

    /** Short provider name, used in logs and as a default category. */
    String name();

    /** All recipes this provider currently knows. Called on the registry's background refresh. */
    List<SbsRecipe> loadRecipes();
}
