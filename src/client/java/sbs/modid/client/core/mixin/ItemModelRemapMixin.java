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
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.MissingItemModel;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.helper.texture.logic.SkyblockItemModels;

/**
 * Restores vanilla item visuals while "Ignore Enforced Texture Packs" is on.
 *
 * <p>Hypixel stamps every SkyBlock item with an {@code item_model} component in the
 * {@code hypixel_skyblock} namespace, whose models only exist inside the enforced server pack – with
 * the pack blocked they all render as the pink/black missing texture. This wraps the component read
 * inside {@code ItemModelResolver.appendItemLayers} and swaps such a model for the item's classic
 * vanilla model from {@link SkyblockItemModels} (the CC0 id to model mapping).
 *
 * <p><b>Unknown ids are future-proofed by a real resolve check</b> rather than an "is the bottom pack
 * cached?" guess: a SkyBlock item Hypixel added after the bundled CC0 index (and after the cached
 * fallback pack was built) is in neither, so returning its {@code hypixel_skyblock} model unchanged
 * rendered the pink/black missing texture. Now the enforced-pack model is kept only when it
 * <i>actually resolves</i> ({@code getItemModel} is not the {@link MissingItemModel}); otherwise the
 * base item's own vanilla model is used, so any future item still shows a sensible icon.
 */
@Mixin(ItemModelResolver.class)
public abstract class ItemModelRemapMixin {

    @WrapOperation(method = "appendItemLayers",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/item/ItemStack;get(Lnet/minecraft/core/component/DataComponentType;)Ljava/lang/Object;"))
    private Object skyblockSimplified$remapModel(ItemStack stack, DataComponentType<?> type,
                                                 Operation<Object> original) {
        Object current = original.call(stack, type);
        if (!(current instanceof Identifier model) || stack.isEmpty()
                || !SkyblockItemModels.HYPIXEL_NAMESPACE.equals(model.getNamespace())
                || !ConfigManager.getInstance().get().texturePack.ignoreEnforcedPacks) {
            return current;
        }
        String skyblockId = SkyblockItemModels.skyblockId(stack);
        if (skyblockId != null) {
            Identifier classic = SkyblockItemModels.modelFor(skyblockId);
            if (classic != null) {
                return classic;
            }
        } else if (SkyblockItemModels.hasCustomKey(stack, "quiver_arrow")) {
            // Quiver arrows carry no SkyBlock id – they are plain arrows visually.
            Identifier arrow = Items.ARROW.components().get(DataComponents.ITEM_MODEL);
            if (arrow != null) {
                return arrow;
            }
        }
        // Unknown id (not in the CC0 index): keep the enforced-pack model ONLY when it genuinely
        // resolves - the bottom fallback pack has it, or some other pack does. A brand-new SkyBlock
        // item is in neither the bundled index nor the cached fallback pack, so its hypixel_skyblock
        // model is missing and would render the pink/black texture; there the base item's own vanilla
        // model is the safe icon. Checking the real model (not "is the pack cached?") future-proofs
        // every item Hypixel adds later.
        if (!(Minecraft.getInstance().getModelManager().getItemModel(model) instanceof MissingItemModel)) {
            return current;
        }
        Identifier fallback = stack.getItem().components().get(DataComponents.ITEM_MODEL);
        return fallback != null ? fallback : current;
    }
}
