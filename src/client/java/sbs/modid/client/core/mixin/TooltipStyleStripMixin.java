/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.helper.texture.logic.SkyblockItemModels;

/**
 * Neutralises Hypixel's custom tooltip style while "Ignore Enforced Texture Packs" is on: items
 * carry a {@code tooltip_style} in the {@code hypixel_skyblock} namespace whose sprites live in the
 * blocked pack – nulling the style here makes every tooltip render with the vanilla frame instead of
 * missing-sprite artifacts. All tooltip paths funnel through {@code tooltip(...)}, so one hook
 * covers both direct and deferred rendering.
 */
@Mixin(GuiGraphicsExtractor.class)
public abstract class TooltipStyleStripMixin {

    @ModifyVariable(method = "tooltip(Lnet/minecraft/client/gui/Font;Ljava/util/List;IILnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipPositioner;Lnet/minecraft/resources/Identifier;)V",
            at = @At("HEAD"), argsOnly = true)
    private Identifier skyblockSimplified$vanillaTooltipStyle(Identifier style) {
        if (style != null && SkyblockItemModels.HYPIXEL_NAMESPACE.equals(style.getNamespace())
                && ConfigManager.getInstance().get().texturePack.ignoreEnforcedPacks) {
            return null;
        }
        return style;
    }
}
