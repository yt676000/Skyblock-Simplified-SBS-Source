/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import sbs.modid.client.ui.theme.RecipeBookSkin;

/**
 * Minecraft Overlay module: marks the moment a recipe book is drawing, so {@link RecipeBookSkin} can
 * swap its sprites and nothing else's.
 *
 * <p>The screen reaches its recipe book through exactly one call in {@code extractRenderState}
 * (javap-confirmed, 26.2). It is a virtual call, so a book another mod substitutes for the vanilla
 * one comes through it too, even when that book's own {@code extractRenderState} never reaches
 * vanilla's. The scope is closed in {@code finally}: a throwing draw must not leave every later
 * sprite in the frame matched.
 */
@Mixin(AbstractRecipeBookScreen.class)
public abstract class RecipeBookScopeMixin {

    @WrapOperation(method = "extractRenderState",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/recipebook/RecipeBookComponent;"
                            + "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V"))
    private void skyblockSimplified$recipeBookScope(RecipeBookComponent<?> book, GuiGraphicsExtractor g,
                                                    int mouseX, int mouseY, float partialTick,
                                                    Operation<Void> original) {
        RecipeBookSkin.begin(book);
        try {
            original.call(book, g, mouseX, mouseY, partialTick);
        } finally {
            RecipeBookSkin.end();
        }
    }
}
