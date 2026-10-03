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
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sbs.modid.client.helper.storage.BlockInteractTracker;

/**
 * Feeds the position of every block the player right-clicks to {@link BlockInteractTracker} (so the
 * storage index can tell island chests apart – they all share the title "Chest") and to the dev
 * {@link sbs.modid.client.core.dev.SecretScanner} (auto-records clicked chest/lever/essence secrets; a
 * no-op unless its scan is armed).
 *
 * <p>Purely observational: it injects at HEAD, never cancels and returns nothing, so block
 * interaction behaves exactly as vanilla whether or not the consumers are enabled.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class BlockInteractMixin {

    @Inject(method = "useItemOn", at = @At("HEAD"))
    private void skyblockSimplified$trackBlock(LocalPlayer player, InteractionHand hand,
                                               BlockHitResult hit,
                                               CallbackInfoReturnable<?> cir) {
        if (hit != null) {
            BlockInteractTracker.record(hit.getBlockPos());
            // Layout Recorder (dev): a right-clicked block can open a screen (furniture, the Harp).
            sbs.modid.client.core.dev.ScreenOpeners.getInstance().onBlockUse(hit);
            sbs.modid.client.helper.fairysouls.logic.FairySoulTracker.getInstance()
                    .onBlockClicked(hit.getBlockPos());
            sbs.modid.client.core.dev.SecretScanner.getInstance().onBlockClicked(hit.getBlockPos());
            // Pest Traps: logs the click so what a trap is, and what emptying it prints, can be read.
            sbs.modid.client.skills.garden.pests.PestProfitTracker.getInstance()
                    .onBlockInteract(hit.getBlockPos());
            // Treasure chests: the player's own click on a chest they are picking clears the spot
            // marker. Observed, never produced - the click is the player's.
            sbs.modid.client.skills.mining.treasurechest.logic.TreasureChestTracker.getInstance()
                    .onBlockClicked(hit.getBlockPos());
            // Honey trees: the click is what identifies WHICH tree was smeared - a chat line never
            // can. This method is the local player's own interaction controller, so another
            // player's smear cannot reach it; that is the whole "local only" guarantee.
            if (player != null) {
                sbs.modid.client.skills.foraging.logic.HoneyTreeTimers.getInstance()
                        .onBlockInteract(hit.getBlockPos(), player.getItemInHand(hand));
                // Not Diana's dig: a burrow is dug with a left click (AttackTrackMixin), and a right
                // click with a spade on the ground is the Echo ability, which ItemUseMixin reads.
            }
        }
    }
}
