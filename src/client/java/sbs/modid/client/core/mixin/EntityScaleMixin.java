/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.helper.visual.model.EntityScaleKind;

/**
 * The Animation &amp; Scaling module's <b>entity scale</b>: per-axis X/Y/Z scaling (0–1000%,
 * 100% = vanilla) applied to yourself, other players and/or all living entities.
 *
 * <p>Extraction tags every state with its entity kind (the render state carries no identity – see
 * {@link EntityRenderStateMixin}); the scale itself is applied in {@code submit} right after the
 * vanilla {@code scale(state, pose)} call, i.e. inside the renderer's own push/pop and after all
 * vanilla sizing (baby scale, {@code state.scale}), so it composes cleanly and every renderer
 * override (players go through {@code AvatarRenderer.scale}) is still honored.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class EntityScaleMixin {

    private static SBSConfig.AnimationScalingSettings sbs$cfg() {
        return ConfigManager.getInstance().get().animationScaling;
    }

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
            at = @At("TAIL"))
    private void skyblockSimplified$tagEntityKind(LivingEntity entity, LivingEntityRenderState state,
                                                  float partialTick, CallbackInfo ci) {
        int kind = EntityScaleKind.KIND_OTHER_ENTITY;
        if (entity == Minecraft.getInstance().player) {
            kind = EntityScaleKind.KIND_SELF;
        } else if (entity instanceof Avatar) {
            kind = sbs$isRealPlayer(entity) ? EntityScaleKind.KIND_OTHER_PLAYER
                    : EntityScaleKind.KIND_NPC;
        }
        EntityScaleKind tag = (EntityScaleKind) state;
        tag.sbs$setScaleKind(kind);
        if (kind == EntityScaleKind.KIND_OTHER_ENTITY) {
            tag.sbs$setScaleTypeId(
                    net.minecraft.world.entity.EntityType.getKey(entity.getType()).toString());
        }
    }

    /**
     * Whether a player-model entity is a real player rather than an NPC.
     *
     * <p>SkyBlock's player-shaped NPCs are spawned as ordinary {@code Avatar} entities, so no class
     * or component test separates them - but only genuine players get an entry in the client's
     * player list, which is what the tab list is built from. NPCs are pushed as world entities
     * alone. A player briefly missing from the list (the entity can arrive a tick before its list
     * entry) is only mis-typed for that tick, and the consequence is one frame at the other group's
     * scale, so no guard is worth the complexity.
     */
    private static boolean sbs$isRealPlayer(LivingEntity entity) {
        return sbs.modid.client.core.player.RealPlayers.isRealPlayer(entity);
    }

    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At(value = "INVOKE", shift = At.Shift.AFTER,
                    target = "Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;scale(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;)V"))
    private void skyblockSimplified$applyEntityScale(LivingEntityRenderState state, PoseStack poseStack,
                                                     SubmitNodeCollector collector, CameraRenderState camera,
                                                     CallbackInfo ci) {
        SBSConfig.AnimationScalingSettings settings = sbs$cfg();
        EntityScaleKind tag = (EntityScaleKind) state;
        boolean apply = switch (tag.sbs$scaleKind()) {
            case EntityScaleKind.KIND_SELF -> settings.scaleSelf || settings.scaleAllEntities;
            case EntityScaleKind.KIND_OTHER_PLAYER -> settings.scaleOtherPlayers || settings.scaleAllEntities;
            // NPCs used to be indistinguishable from remote players and so followed that toggle.
            // They still do by default - scaleNpcs starts on - but can now be excluded on their own,
            // which is the whole point: a shop NPC at 300% blocks the menu behind it.
            case EntityScaleKind.KIND_NPC ->
                    settings.scaleNpcs && (settings.scaleOtherPlayers || settings.scaleAllEntities);
            default -> settings.scaleAllEntities && sbs$typeSelected(settings, tag.sbs$scaleTypeId());
        };
        if (!apply) {
            return;
        }
        float x = sbs$factor(settings.entityScaleX);
        float y = sbs$factor(settings.entityScaleY);
        float z = sbs$factor(settings.entityScaleZ);
        if (x == 1.0F && y == 1.0F && z == 1.0F) {
            return;
        }
        poseStack.scale(x, y, z);
    }

    /**
     * Whether a non-player entity type is part of the selection. An <b>empty</b> selection means
     * "every type", which is what keeps {@code scaleAllEntities} behaving exactly as it always has
     * for anyone who never opens the picker; listing even one type narrows it to that list.
     */
    private static boolean sbs$typeSelected(SBSConfig.AnimationScalingSettings settings, String typeId) {
        return settings.scaleEntityTypes.isEmpty()
                || (typeId != null && settings.scaleEntityTypes.contains(typeId));
    }

    /** Percent → factor, clamped to 0.01–10 (a true 0 would produce a degenerate matrix). */
    private static float sbs$factor(int percent) {
        return Math.max(1, Math.min(percent, 1000)) / 100.0F;
    }
}
