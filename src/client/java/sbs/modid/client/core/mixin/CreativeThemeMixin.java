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
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.CreativeModeTab;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.ui.theme.CreativeSkin;
import sbs.modid.client.ui.theme.SBSTheme;

/**
 * Minecraft Overlay module: opens {@link CreativeSkin}'s scope for exactly the creative screen's
 * background pass, and recolours its tab title for the dark panel.
 *
 * <p>The scope is opened at the head of {@code extractBackground} and closed at its return; a draw
 * that throws leaves it open only until the next frame's head resets it, and it only ever matches the
 * creative screen's own sprite ids, so nothing else can be affected meanwhile. Field names
 * javap-verified against 26.2: {@code searchBox}, {@code destroyItemSlot}, static {@code selectedTab}.
 */
@Mixin(CreativeModeInventoryScreen.class)
public abstract class CreativeThemeMixin {

    @Shadow
    private EditBox searchBox;
    @Shadow
    private Slot destroyItemSlot;
    @Shadow
    private static CreativeModeTab selectedTab;

    @Inject(method = "extractBackground", at = @At("HEAD"))
    private void skyblockSimplified$creativeScopeOpen(GuiGraphicsExtractor g, int mouseX, int mouseY,
                                                      float partialTick, CallbackInfo ci) {
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        AbstractContainerScreenAccessor bounds = (AbstractContainerScreenAccessor) this;
        CreativeSkin.begin(self.getMenu(), bounds.skyblockSimplified$imageWidth(),
                bounds.skyblockSimplified$imageHeight(), searchBox, destroyItemSlot, selectedTab);
    }

    @Inject(method = "extractBackground", at = @At("RETURN"))
    private void skyblockSimplified$creativeScopeClose(GuiGraphicsExtractor g, int mouseX, int mouseY,
                                                       float partialTick, CallbackInfo ci) {
        CreativeSkin.end();
    }

    /** The tab title is drawn in vanilla's near-black (0xFF404040), unreadable on the SBS panel. */
    @WrapOperation(method = "extractLabels", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;"
                    + "Lnet/minecraft/network/chat/Component;IIIZ)V"))
    private void skyblockSimplified$creativeTitleColour(GuiGraphicsExtractor g, Font font, Component text,
                                                        int x, int y, int color, boolean shadow,
                                                        Operation<Void> original) {
        original.call(g, font, text, x, y, CreativeSkin.themed() ? SBSTheme.TEXT : color, shadow);
    }
}
