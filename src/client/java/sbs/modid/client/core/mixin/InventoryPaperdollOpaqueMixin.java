/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sbs.modid.client.helper.visual.transparency.OwnPlayerAlpha;
import sbs.modid.client.helper.visual.transparency.OwnPlayerTransparency;

/**
 * Own Player Transparency stays out of GUI models. Every entity drawn in a menu - the inventory
 * paperdoll (the real local player), the Loadouts / Armor Sets previews - has its render state built
 * by this one private helper (verified on the 26.2 jar: {@code extractEntityInInventoryFollowsMouse}
 * calls it); the world-render tag set during extraction is cleared here, so the model is opaque.
 */
@Mixin(InventoryScreen.class)
public abstract class InventoryPaperdollOpaqueMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;",
            at = @At("RETURN"))
    private static void skyblockSimplified$opaqueInGui(LivingEntity entity,
                                                       CallbackInfoReturnable<EntityRenderState> cir) {
        if (cir.getReturnValue() instanceof OwnPlayerAlpha tagged) {
            tagged.sbs$setOwnAlpha(OwnPlayerTransparency.OPAQUE);
        }
    }
}
