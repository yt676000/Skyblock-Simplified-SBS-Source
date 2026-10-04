/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sbs.modid.client.combat.damage.logic.DamageAttribution;

/**
 * Feeds the Damage Attribution matcher every attack trigger: melee swings on an entity, bow
 * releases, and right-click ability casts. Purely observational – injects at HEAD, never cancels,
 * sends nothing; the module only uses the timestamps to open its client-side matching windows.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class AttackTrackMixin {

    @Inject(method = "attack", at = @At("HEAD"))
    private void skyblockSimplified$onAttack(Player player, Entity target, CallbackInfo ci) {
        DamageAttribution.getInstance().onMeleeAttack(target);
        sbs.modid.client.combat.damage.logic.DamageEstimator.getInstance().onMeleeAttack(target);
        // Fairy Souls: what was clicked is how a collection line gets attributed to one soul.
        sbs.modid.client.helper.fairysouls.logic.FairySoulTracker.getInstance()
                .onEntityClicked(target);
    }

    @Inject(method = "interact", at = @At("HEAD"))
    private void skyblockSimplified$onInteract(Player player, Entity target,
                                               net.minecraft.world.phys.EntityHitResult hit,
                                               InteractionHand hand,
                                               CallbackInfoReturnable<?> cir) {
        sbs.modid.client.helper.fairysouls.logic.FairySoulTracker.getInstance()
                .onEntityClicked(target);
        // Pest Traps: a trap may be an entity - the click is logged, never acted on.
        sbs.modid.client.skills.garden.pests.PestProfitTracker.getInstance().onEntityInteract(target);
        // Layout Recorder (dev): the right-clicked NPC / entity becomes the root of the click path.
        sbs.modid.client.core.dev.ScreenOpeners.getInstance().onEntityInteract(target);
        // Reward chests: they are opened through an armor stand, so this - not BlockInteractMixin -
        // is where the client learns which chest a preview belongs to.
        sbs.modid.client.dungeons.chest.RewardChestGlow.getInstance().onEntityInteract(target);
    }

    @Inject(method = "startDestroyBlock", at = @At("HEAD"))
    private void skyblockSimplified$onHitBlock(net.minecraft.core.BlockPos pos,
                                               net.minecraft.core.Direction direction,
                                               CallbackInfoReturnable<Boolean> cir) {
        sbs.modid.client.helper.fairysouls.logic.FairySoulTracker.getInstance().onBlockClicked(pos);
        // Diana: which block was dug. A burrow is dug with a left click, and this is the left click
        // on a block - the right click on the ground is the spade's Echo ability, read in
        // ItemUseMixin. The chat lines that follow a dig carry no coordinates, so this is the only
        // thing that can attach them to a burrow, and it is where the next arrow is drawn from.
        sbs.modid.client.combat.diana.logic.DianaGuard.run(sbs.modid.client.combat.diana.logic.DianaGuard.Hook.DIG, pos, () -> {
            net.minecraft.client.player.LocalPlayer player = net.minecraft.client.Minecraft.getInstance().player;
            if (player != null && sbs.modid.client.combat.diana.logic.DianaEvent.isSpade(
                    sbs.modid.client.helper.texture.logic.SkyblockItemModels
                            .skyblockId(player.getMainHandItem()))) {
                sbs.modid.client.combat.diana.logic.BurrowDetector.getInstance().onBlockDug(pos);
            }
        });
    }

    @Inject(method = "releaseUsingItem", at = @At("HEAD"))
    private void skyblockSimplified$onRelease(Player player, CallbackInfo ci) {
        DamageAttribution.getInstance().onRangedTrigger();
    }

    @Inject(method = "useItem", at = @At("HEAD"))
    private void skyblockSimplified$onUseItem(Player player, InteractionHand hand,
                                              CallbackInfoReturnable<?> cir) {
        DamageAttribution.getInstance().onRangedTrigger();
    }
}
