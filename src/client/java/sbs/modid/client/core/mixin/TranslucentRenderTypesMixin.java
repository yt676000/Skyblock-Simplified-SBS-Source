/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sbs.modid.client.helper.visual.transparency.OwnPlayerTransparency;

/**
 * Own Player Transparency: while the local player's faded state submits
 * ({@link OwnPlayerTransparency#scope}), the opaque entity render types ask for are handed out as
 * their translucent twins - a cutout or solid type ignores the colour alpha, so without this the
 * fade would only reach the parts that were translucent already. Armour keeps its armour pipeline
 * ({@code armorTranslucent}); everything else becomes {@code entityTranslucent}. Both are memoised
 * by vanilla, so the swap allocates nothing per frame. Outside the scope: one boolean check.
 */
@Mixin(RenderTypes.class)
public abstract class TranslucentRenderTypesMixin {

    @Inject(method = "armorCutoutNoCull(Lnet/minecraft/resources/Identifier;)Lnet/minecraft/client/renderer/rendertype/RenderType;",
            at = @At("HEAD"), cancellable = true)
    private static void skyblockSimplified$armor(Identifier texture, CallbackInfoReturnable<RenderType> cir) {
        if (OwnPlayerTransparency.scope) {
            cir.setReturnValue(RenderTypes.armorTranslucent(texture));
        }
    }

    @Inject(method = "createArmorDecalCutoutNoCull(Lnet/minecraft/resources/Identifier;)Lnet/minecraft/client/renderer/rendertype/RenderType;",
            at = @At("HEAD"), cancellable = true)
    private static void skyblockSimplified$armorDecal(Identifier texture, CallbackInfoReturnable<RenderType> cir) {
        if (OwnPlayerTransparency.scope) {
            cir.setReturnValue(RenderTypes.armorTranslucent(texture));
        }
    }

    @Inject(method = {
            "entitySolid(Lnet/minecraft/resources/Identifier;)Lnet/minecraft/client/renderer/rendertype/RenderType;",
            "entitySolidZOffsetForward(Lnet/minecraft/resources/Identifier;)Lnet/minecraft/client/renderer/rendertype/RenderType;",
            "entityCutoutCull(Lnet/minecraft/resources/Identifier;)Lnet/minecraft/client/renderer/rendertype/RenderType;",
            "entityCutout(Lnet/minecraft/resources/Identifier;)Lnet/minecraft/client/renderer/rendertype/RenderType;",
            "entityCutoutZOffset(Lnet/minecraft/resources/Identifier;)Lnet/minecraft/client/renderer/rendertype/RenderType;"
    }, at = @At("HEAD"), cancellable = true)
    private static void skyblockSimplified$entity(Identifier texture, CallbackInfoReturnable<RenderType> cir) {
        if (OwnPlayerTransparency.scope) {
            cir.setReturnValue(RenderTypes.entityTranslucent(texture));
        }
    }

    @Inject(method = {
            "entityCutout(Lnet/minecraft/resources/Identifier;Z)Lnet/minecraft/client/renderer/rendertype/RenderType;",
            "entityCutoutZOffset(Lnet/minecraft/resources/Identifier;Z)Lnet/minecraft/client/renderer/rendertype/RenderType;"
    }, at = @At("HEAD"), cancellable = true)
    private static void skyblockSimplified$entityFlag(Identifier texture, boolean flag,
                                                      CallbackInfoReturnable<RenderType> cir) {
        if (OwnPlayerTransparency.scope) {
            cir.setReturnValue(RenderTypes.entityTranslucent(texture, flag));
        }
    }
}
