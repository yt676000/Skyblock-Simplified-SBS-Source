/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.core.dev.M7DragonProbe;

/**
 * The two entity packets, handed to {@link M7DragonProbe} while a capture is armed: the spawn packet
 * and the metadata packet.
 *
 * <p><b>Why the packet and not the entity.</b> For the metadata half this is not a preference, it is
 * the only place the value exists. {@code EnderDragon.aiStep} runs {@code setHealth(getHealth())} on
 * the client every tick, and {@code LivingEntity.setHealth} clamps to {@code getMaxHealth()} - the
 * vanilla dragon attribute of 200 unless the server sends an attribute update. Whatever SkyBlock
 * health arrived in the metadata is therefore destroyed one tick later, and
 * {@code dragon.getHealth()} answers 200 forever. The packet is the only reading that is not a lie.
 *
 * <p>At TAIL rather than HEAD so this only runs on the main-thread invocation: the netty thread
 * re-dispatches before the body executes, which is what lets {@link M7DragonProbe} keep plain
 * collections. {@link ParticleProbeMixin} and {@link TimeUpdateMixin} lean on the same property, for
 * the same reason. At the TAIL of {@code handleSetEntityData} the values have already been assigned
 * to the entity, which does not matter here - the packet is read, not the entity.
 *
 * <p>Costs one static boolean read per packet while disarmed, and nothing at all is ever cancelled.
 */
@Mixin(ClientPacketListener.class)
public class EntityDataProbeMixin {

    @Inject(method = "handleSetEntityData", at = @At("TAIL"))
    private void skyblockSimplified$probeEntityData(ClientboundSetEntityDataPacket packet, CallbackInfo ci) {
        if (M7DragonProbe.ARMED) {
            M7DragonProbe.getInstance().onEntityData(packet);
        }
    }

    @Inject(method = "handleAddEntity", at = @At("TAIL"))
    private void skyblockSimplified$probeAddEntity(ClientboundAddEntityPacket packet, CallbackInfo ci) {
        if (M7DragonProbe.ARMED) {
            M7DragonProbe.getInstance().onAddEntity(packet);
        }
    }
}
