/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.item.SkyblockItem;
import sbs.modid.client.helper.visual.logic.TextReplacer;

import java.util.Map;

/**
 * Live item-name transformation at the single point every displayed name flows through
 * ({@code ItemStack#getHoverName}):
 * <ol>
 *   <li><b>Persistent renamer</b>: if the item's identity (SkyBlock uuid / id) has a stored
 *       rename, that name is shown – purely client-side, surviving restarts and server swaps
 *       because it is re-applied on read instead of written into the stack.</li>
 *   <li><b>Text Editor</b>: the global text replacements are applied to the (possibly renamed)
 *       name.</li>
 * </ol>
 */
@Mixin(ItemStack.class)
public abstract class ItemNameMixin {

    @Inject(method = "getHoverName", at = @At("RETURN"), cancellable = true)
    private void skyblockSimplified$transformName(CallbackInfoReturnable<Component> cir) {
        SBSConfig config = ConfigManager.getInstance().get();
        ItemStack self = (ItemStack) (Object) this;

        String result = null;

        Map<String, String> renames = config.itemOverlay.itemRenames;
        if (config.itemOverlay.itemRenamer && !renames.isEmpty()) {
            String key = SkyblockItem.uniqueKey(self);
            if (key != null) {
                result = renames.get(key);
            }
        }

        Component original = cir.getReturnValue();
        if (original == null) {
            return;
        }
        // A rename is the player's own text, so it has no style of its own – it inherits the name's,
        // which is where the rarity colour lives. Without that it would render plain white.
        Component base = result != null
                ? Component.literal(result).setStyle(original.getStyle().withItalic(false))
                : original;
        Component replaced = TextReplacer.getInstance().apply(base);

        if (result != null || replaced != base) {
            cir.setReturnValue(replaced);
        }
    }
}
