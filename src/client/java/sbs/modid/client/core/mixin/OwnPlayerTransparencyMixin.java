/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.helper.visual.transparency.OwnPlayerAlpha;
import sbs.modid.client.helper.visual.transparency.OwnPlayerTransparency;
import sbs.modid.client.helper.visual.transparency.TranslucentSubmitCollector;

/**
 * Own Player Transparency (Third Person module): fades the local player's own model in the world.
 *
 * <p>Vanilla's see-through body for invisible entities is the model (verified on the 26.2 jar):
 * {@code LivingEntityRenderer.submit} asks {@code getRenderType(state, bodyVisible, translucent,
 * glowing)} and passes a fixed {@code 0x26FFFFFF} colour. That fades the body only; here the whole
 * player fades together - armour, trims, skull, cape, elytra, held items - so instead of the body
 * call the whole submit runs with a fading collector and a render-type swap
 * ({@code TranslucentRenderTypesMixin}).
 *
 * <ul>
 *   <li>extraction (TAIL): tag the state with the fade; every state is written every frame, since
 *       render states are reused between entities;</li>
 *   <li>submit (HEAD): open the scope and hand the submit the fading collector; (TAIL) close it.</li>
 * </ul>
 * Off, both hooks cost one boolean check. The inventory paperdoll draws the same entity through the
 * GUI path, which clears the tag again ({@code InventoryPaperdollOpaqueMixin}).
 */
@Mixin(LivingEntityRenderer.class)
public abstract class OwnPlayerTransparencyMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
            at = @At("TAIL"))
    private void skyblockSimplified$tagOwnAlpha(LivingEntity entity, LivingEntityRenderState state,
                                                float partialTick, CallbackInfo ci) {
        SBSConfig.ThirdPersonSettings cfg = ConfigManager.getInstance().get().thirdPerson;
        int alpha = OwnPlayerTransparency.OPAQUE;
        if (cfg.ownTransparency) {
            Minecraft minecraft = Minecraft.getInstance();
            alpha = OwnPlayerTransparency.alphaFor(true, entity == minecraft.player,
                    cfg.ownTransparencyThirdPersonOnly, minecraft.options.getCameraType().isFirstPerson(),
                    cfg.ownOpacity);
        }
        ((OwnPlayerAlpha) state).sbs$setOwnAlpha(alpha);
    }

    @ModifyVariable(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At("HEAD"), argsOnly = true)
    private SubmitNodeCollector skyblockSimplified$fadeCollector(SubmitNodeCollector collector,
                                                                 @Local(argsOnly = true) LivingEntityRenderState state) {
        int alpha = ((OwnPlayerAlpha) state).sbs$ownAlpha();
        if (alpha >= OwnPlayerTransparency.OPAQUE) {
            OwnPlayerTransparency.scope = false;   // also heals a scope a thrown submit left open
            return collector;
        }
        OwnPlayerTransparency.scope = true;
        OwnPlayerTransparency.scopeAlpha = alpha;
        return TranslucentSubmitCollector.wrap(collector, alpha);
    }

    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At("TAIL"))
    private void skyblockSimplified$closeScope(CallbackInfo ci) {
        OwnPlayerTransparency.scope = false;
    }
}
