/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.theme;

import net.minecraft.client.gui.components.AbstractWidget;

/**
 * Duck interface mixed onto {@code AbstractRecipeBookScreen}: hands out the vanilla recipe-book
 * toggle button. The screen builds it in a private method and keeps no field for it, so there is
 * nothing an {@code @Accessor} could read.
 *
 * <p>The container reskin is what needs it. Widgets are drawn at the very top of
 * {@code extractContents}, before the labels and long before {@code extractSlots} - and the SBS
 * panel is painted from the head of {@code extractSlots}, over the whole GUI rectangle. The button
 * sits inside that rectangle, so it disappears under the panel while staying perfectly clickable:
 * reported as "I can click it, but only if I already know where it is".
 */
public interface RecipeBookButtonHolder {

    /** The recipe-book toggle, or {@code null} on a screen that never built one. */
    AbstractWidget skyblockSimplified$recipeBookButton();
}
