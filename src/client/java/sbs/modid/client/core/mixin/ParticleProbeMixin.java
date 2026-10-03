/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.core.dev.M7DragonProbe;
import sbs.modid.client.core.dev.ParticleProbe;
import sbs.modid.client.helper.floordrop.logic.FloorDropParticles;

/**
 * The particle packet, handed to {@link ParticleProbe} while a capture is armed.
 *
 * <p><b>The packet and not the particle.</b> {@code ParticleEngine#createParticle} carries no count
 * and no grouping, and it is downstream of both the Particles module's per-type filter and vanilla's
 * particle-count setting - anything reading there is reading what survived, not what was sent.
 *
 * <p>At TAIL rather than HEAD so this only runs on the main-thread invocation: the netty thread
 * re-dispatches before the body executes, which is what lets {@link ParticleProbe} keep plain
 * collections. {@link TimeUpdateMixin} leans on the same property, for the same reason.
 *
 * <p>Costs one static boolean read per packet while disarmed, and nothing at all is ever cancelled.
 */
@Mixin(ClientPacketListener.class)
public class ParticleProbeMixin {

    @Inject(method = "handleParticleEvent", at = @At("TAIL"))
    private void skyblockSimplified$probeParticles(ClientboundLevelParticlesPacket packet, CallbackInfo ci) {
        if (ParticleProbe.ARMED) {
            ParticleProbe.getInstance().onParticlePacket(packet);
        }
        // The M7 dragon capture reads the same packet for a narrower question - whether the dragon
        // spawn effect is a flame packet at a fixed shape. Two consumers rather than one probe doing
        // both: this one scores each packet against those criteria and pairs it with the phase gate
        // and the dragon metadata, which is not what a general particle capture is for.
        if (M7DragonProbe.ARMED) {
            M7DragonProbe.getInstance().onParticlePacket(packet);
        }
        // Floor Drops: the green particles around a floor drop, which the protocol does not attach
        // to the display entity they surround - the packet carries a position and no entity id at
        // all, so the only link a client can make is spatial. Kept beside the two probes rather than
        // on a mixin of its own for the reason they are here: one hook, one main-thread invocation,
        // and a static boolean read while nobody is listening.
        if (FloorDropParticles.LISTENING) {
            FloorDropParticles.getInstance().onParticlePacket(packet);
        }
        // Diana: burrows, the spade's arc and the arrows are all packet-level facts - the count and
        // the speed are what tell a mob burrow from a footstep, and both are gone by the time a
        // particle exists. Gated behind one cached boolean, so a Hub full of particles costs a field
        // read per packet while the toolkit is off or the event is not running.
        sbs.modid.client.combat.diana.logic.DianaGuard.run(sbs.modid.client.combat.diana.logic.DianaGuard.Hook.PARTICLES, packet,
                () -> sbs.modid.client.combat.diana.logic.BurrowDetector.getInstance().onParticlePacket(packet));
        // Treasure chests: the lockpick burst, gated to the bounding box of a chest the player
        // uncovered. Returns on an empty-list check while no chest is claimed.
        sbs.modid.client.skills.mining.treasurechest.logic.TreasureChestTracker.getInstance()
                .onParticlePacket(packet);
    }
}
