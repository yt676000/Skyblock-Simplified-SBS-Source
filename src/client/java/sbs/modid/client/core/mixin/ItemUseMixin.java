/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sbs.modid.client.combat.cooldowns.AbilityCooldowns;
import sbs.modid.client.combat.statbuffs.logic.StatBuffTracker;

/**
 * Notifies the {@link AbilityCooldowns} clock whenever the player uses an item (right-click in air
 * or on a block) – the moment a SkyBlock ability (and its lore-declared cooldown, or the wither
 * blades' fixed Wither Shield 5s) starts. Signatures verified identical on 26.1.2 and 26.2.
 *
 * <p>{@link StatBuffTracker} is told about the same click: a right-click is the one unambiguous
 * "an ability may have just started" signal the client owns, and it is what arms the stat
 * measurement that follows. Both listeners gate themselves - this hook stays a two-line fan-out.
 */
@Mixin(MultiPlayerGameMode.class)
public class ItemUseMixin {

    @Inject(method = "useItem", at = @At("HEAD"))
    private void skyblockSimplified$onUseItem(Player player, InteractionHand hand,
                                              CallbackInfoReturnable<?> cir) {
        if (player != null) {
            AbilityCooldowns.getInstance().onItemUse(player.getItemInHand(hand));
            sbs.modid.client.combat.cooldowns.AbilityReadyAlert.getInstance().onItemUse(player.getItemInHand(hand));
            StatBuffTracker.getInstance().onItemUse(player.getItemInHand(hand));
            sbs.modid.client.helper.buffs.consumables.logic.ConsumableStore.getInstance().onItemUse(player.getItemInHand(hand));
            skyblockSimplified$dianaSpade(player, hand);
            // Layout Recorder (dev): the SkyBlock Menu, Abiphone... open screens from a held item.
            sbs.modid.client.core.dev.ScreenOpeners.getInstance().onItemUse(player.getItemInHand(hand));
        }
    }

    /**
     * A right-click with a Griffin spade in hand is the only signal that the ability has fired, and
     * it is what starts the arc collection. Nothing is cancelled: the ability is the player's.
     *
     * <p><b>Called from both entry points, and that is the whole point of it being a method.</b>
     * Vanilla splits a right-click in two: {@code useItem} when the crosshair is on nothing,
     * {@code useItemOn} when it is on a block. Which one fires is decided by where the player
     * happens to be looking - and firing the spade's ability at the ground, which is what everybody
     * does, goes through {@code useItemOn}. Wiring only {@code useItem} left the arc collection
     * armed exclusively for players aiming at the sky, so the spade guess produced nothing.
     */
    @org.spongepowered.asm.mixin.Unique
    private static void skyblockSimplified$dianaSpade(Player player, InteractionHand hand) {
        sbs.modid.client.combat.diana.logic.DianaGuard.run(sbs.modid.client.combat.diana.logic.DianaGuard.Hook.ABILITY, hand, () -> {
            if (sbs.modid.client.combat.diana.logic.DianaEvent.isSpade(
                    sbs.modid.client.helper.texture.logic.SkyblockItemModels
                            .skyblockId(player.getItemInHand(hand)))) {
                sbs.modid.client.combat.diana.logic.SpadeGuess.getInstance().onAbilityUsed();
            }
        });
    }

    @Inject(method = "useItemOn", at = @At("HEAD"))
    private void skyblockSimplified$onUseItemOn(LocalPlayer player, InteractionHand hand, BlockHitResult hit,
                                                CallbackInfoReturnable<?> cir) {
        if (player != null) {
            AbilityCooldowns.getInstance().onItemUse(player.getItemInHand(hand));
            sbs.modid.client.combat.cooldowns.AbilityReadyAlert.getInstance().onItemUse(player.getItemInHand(hand));
            StatBuffTracker.getInstance().onItemUse(player.getItemInHand(hand));
            sbs.modid.client.helper.buffs.consumables.logic.ConsumableStore.getInstance().onItemUse(player.getItemInHand(hand));
            skyblockSimplified$dianaSpade(player, hand);
        }
    }
}
