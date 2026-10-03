/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sbs.modid.client.helper.visual.logic.TextReplacer;

import java.util.ArrayList;
import java.util.List;

/**
 * Text Editor in tooltips: applies the replacement rules on the client display path –
 * {@code Screen.getTooltipFromItem} is what every container / inventory tooltip goes through right
 * before rendering, so rules reliably affect item names (line 1) and lore in every SkyBlock menu.
 * Only lines that actually match a rule are rebuilt, and a rebuilt line keeps the colours of every
 * run the rule did not touch – the replacement itself inherits the colour it was spliced into.
 */
@Mixin(Screen.class)
public abstract class ScreenTooltipReplaceMixin {

    @Inject(method = "getTooltipFromItem", at = @At("RETURN"), cancellable = true)
    private static void skyblockSimplified$replaceTooltip(Minecraft minecraft, ItemStack stack,
                                                          CallbackInfoReturnable<List<Component>> cir) {
        if (!TextReplacer.getInstance().hasRules()) {
            return;
        }
        List<Component> current = cir.getReturnValue();
        if (current == null || current.isEmpty()) {
            return;
        }
        List<Component> result = null;
        for (int i = 0; i < current.size(); i++) {
            Component original = current.get(i);
            Component replaced = TextReplacer.getInstance().apply(original);
            if (replaced != original) {
                if (result == null) {
                    result = new ArrayList<>(current);
                }
                result.set(i, replaced);
            }
        }
        if (result != null) {
            cir.setReturnValue(result);
        }
    }
}
