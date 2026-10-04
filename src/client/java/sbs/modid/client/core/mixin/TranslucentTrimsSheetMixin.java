/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sbs.modid.client.helper.visual.transparency.OwnPlayerTransparency;

/**
 * Own Player Transparency: armour trims come from {@code Sheets.armorTrimsSheet}, not from a
 * {@code RenderTypes} factory, so they get the same translucent swap here (see
 * {@code TranslucentRenderTypesMixin}).
 */
@Mixin(Sheets.class)
public abstract class TranslucentTrimsSheetMixin {

    @Inject(method = "armorTrimsSheet(Z)Lnet/minecraft/client/renderer/rendertype/RenderType;",
            at = @At("HEAD"), cancellable = true)
    private static void skyblockSimplified$trims(boolean decal, CallbackInfoReturnable<RenderType> cir) {
        if (OwnPlayerTransparency.scope) {
            cir.setReturnValue(RenderTypes.armorTranslucent(Sheets.ARMOR_TRIMS_SHEET));
        }
    }
}
