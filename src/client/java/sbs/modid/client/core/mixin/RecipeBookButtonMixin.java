/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import sbs.modid.client.ui.theme.RecipeBookButtonHolder;

/**
 * Remembers the recipe-book toggle button so {@link SbsContainerThemeMixin} can draw it back on top
 * of the SBS panel that buries it. Changes nothing by itself.
 *
 * <p>{@code initButton} builds the button inline and hands it straight to
 * {@code addRenderableWidget}; the screen keeps no reference, so the argument on its way past is the
 * only place to take one. {@code @ModifyArg} returns it untouched - this is a read, spelled as a
 * write because Mixin has no "observe an argument" injector.
 *
 * <p>The one call the screen makes to {@code addWidget} (the recipe book panel itself) has a
 * different name and is not matched, so the field only ever holds the button.
 */
@Mixin(AbstractRecipeBookScreen.class)
public abstract class RecipeBookButtonMixin implements RecipeBookButtonHolder {

    @Unique
    private AbstractWidget skyblockSimplified$recipeButton;

    @Override
    public AbstractWidget skyblockSimplified$recipeBookButton() {
        return skyblockSimplified$recipeButton;
    }

    @ModifyArg(method = "initButton",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/inventory/AbstractRecipeBookScreen;"
                            + "addRenderableWidget(Lnet/minecraft/client/gui/components/events/GuiEventListener;)"
                            + "Lnet/minecraft/client/gui/components/events/GuiEventListener;"),
            index = 0)
    private GuiEventListener skyblockSimplified$captureRecipeButton(GuiEventListener button) {
        if (button instanceof AbstractWidget widget) {
            skyblockSimplified$recipeButton = widget;
        }
        return button;
    }
}
