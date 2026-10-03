/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.resources.model.ModelManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemIcons;

import java.util.concurrent.CompletableFuture;

/**
 * Re-resolves the Recipe Viewer icon cache after every resource reload. Custom Hypixel items carry
 * their look as a resource-pack item model ({@code hypixel_skyblock:item/...}) that only exists
 * while Hypixel's server resource pack is active – icons built before the pack loaded (or after it
 * unloaded) must re-evaluate that model's availability.
 */
@Mixin(ModelManager.class)
public abstract class ItemModelReloadMixin {

    @Inject(method = "reload", at = @At("RETURN"))
    private void skyblockSimplified$invalidateItemIcons(CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        cir.getReturnValue().thenRun(() -> SkyBlockItemIcons.getInstance().invalidate());
    }
}
