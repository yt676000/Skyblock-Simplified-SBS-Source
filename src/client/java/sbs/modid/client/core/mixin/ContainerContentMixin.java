/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.skills.hunting.logic.AttributeMenuReader;

/**
 * "The open container's contents changed" - the signal a menu reader has to have.
 *
 * <p><b>Why polling was not enough.</b> Hypixel opens a menu and fills its slots afterwards, over
 * several packets: the screen exists, the title is right, and for a moment the slots are empty or
 * half-populated. A reader that scans once on open therefore reads a menu that is not there yet, and
 * one that scans on a timer reads it correctly only by luck. Watching the packets that actually
 * carry the items is the direct answer to the question, and it is what makes the first scan land on
 * a full menu rather than on whatever had arrived by then.
 *
 * <p><b>It marks, it does not scan.</b> This runs on the network path for every container packet in
 * the game, so it does one static call and returns; the reader picks the mark up on the next client
 * tick and does its work there. That keeps slot reading on the main thread - where
 * {@code AbstractContainerMenu.slots} is safe to walk - and collapses a burst of packets into one
 * scan instead of one per packet.
 *
 * <p>Nothing is ever cancelled and no packet is modified. At {@code TAIL} so vanilla has already
 * applied the items: at {@code HEAD} the reader would be told about a change and then read the
 * contents from before it.
 */
@Mixin(ClientPacketListener.class)
public class ContainerContentMixin {

    /** A whole container's contents at once - what an opening menu is filled by. */
    @Inject(method = "handleContainerContent", at = @At("TAIL"))
    private void skyblockSimplified$contentChanged(ClientboundContainerSetContentPacket packet,
                                                   CallbackInfo ci) {
        AttributeMenuReader.getInstance().onContainerUpdated();
    }

    /** One slot - what a menu that is still filling in, or reacting to a click, sends. */
    @Inject(method = "handleContainerSetSlot", at = @At("TAIL"))
    private void skyblockSimplified$slotChanged(ClientboundContainerSetSlotPacket packet,
                                                CallbackInfo ci) {
        AttributeMenuReader.getInstance().onContainerUpdated();
    }
}
