/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.particles;

import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.resources.Identifier;
import sbs.modid.client.combat.diana.logic.BurrowDetector;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.helper.particles.logic.ParticleBudget;
import sbs.modid.client.skills.mining.precision.logic.PrecisionMiningTracker;
import sbs.modid.client.skills.mining.treasurechest.logic.TreasureChestTracker;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * "Limit particle spam" (Particles module): a per-type rate cap on top of {@link ParticleFilter}.
 *
 * <p>Slots in the {@link ParticleBudget} are the particle registry's own int ids - a registry lookup
 * per spawn, no map, no allocation - plus one extra slot for block-break effects, which never reach
 * {@code createParticle} (see {@code BlockBreakParticleMixin}). Buckets refill on the particle
 * engine's tick, which is the client tick.
 *
 * <p><b>Feature particles are never thinned.</b> While a server particle packet is being expanded
 * ({@code ParticleLimiterPacketMixin} brackets it), an empty bucket asks the features' own signature
 * gates - the Diana role table (type + count + speed + spread), the claimed treasure chests' lockpick
 * box, the Precision Mining probe radius - and a claimed packet passes whole. The check runs at most
 * once per packet and only once a type is actually over its cap. Detection itself is unaffected
 * either way: those features read the packet at the handler's TAIL, not the spawned particles.
 *
 * <p>Client thread only, like every particle spawn.
 */
public final class ParticleLimiter {

    private static ParticleBudget budget;
    private static int blockBreakSlot;
    private static String[] typeIds;
    private static boolean dirty = true;
    private static long lastRollAt;

    // ------------------------------------------------------------------ packet context

    private static ClientboundLevelParticlesPacket packet;
    private static int packetSlot = -1;
    /** 0 = not asked yet for this packet, 1 = claimed by a feature, 2 = not claimed. */
    private static int packetVerdict;

    private static final ParticleBudget.Exemption PACKET_EXEMPTION = ParticleLimiter::packetClaimed;

    private ParticleLimiter() {
    }

    private static SBSConfig.ParticleSettings cfg() {
        return ConfigManager.getInstance().get().particles;
    }

    /** Re-reads the caps before the next spawn - called on every edit of the limiter rows. */
    public static void invalidate() {
        dirty = true;
    }

    /** {@code createParticle} HEAD, after the per-type filter. {@code false} = drop this particle. */
    public static boolean admit(ParticleType<?> type) {
        if (!cfg().limitSpam || type == null) {
            return true;
        }
        ParticleBudget b = ensureBudget();
        int slot = BuiltInRegistries.PARTICLE_TYPE.getId(type);
        if (b.cap(slot) == ParticleBudget.UNLIMITED) {
            return true;
        }
        return b.admit(slot, packet != null && packetSlot == slot ? PACKET_EXEMPTION : null);
    }

    /** {@code addDestroyBlockEffect} HEAD. {@code false} = skip this block's break dust. */
    public static boolean admitBlockBreak() {
        if (!cfg().limitSpam) {
            return true;
        }
        return ensureBudget().admit(blockBreakSlot, null);
    }

    /** Particle engine tick: refill every bucket, publish the per-second drop counts. */
    public static void onTick() {
        if (!cfg().limitSpam) {
            return;
        }
        ParticleBudget b = ensureBudget();
        b.refill();
        long now = System.currentTimeMillis();
        if (now - lastRollAt >= 1_000L) {
            lastRollAt = now;
            b.rollSecond();
        }
    }

    /** {@code handleParticleEvent}, main-thread invocation, before the particles are spawned. */
    public static void beginPacket(ClientboundLevelParticlesPacket p) {
        if (!cfg().limitSpam || p == null || p.getParticle() == null) {
            return;
        }
        packet = p;
        packetSlot = BuiltInRegistries.PARTICLE_TYPE.getId(p.getParticle().getType());
        packetVerdict = 0;
    }

    /** {@code handleParticleEvent} TAIL. */
    public static void endPacket() {
        packet = null;
        packetSlot = -1;
    }

    private static boolean packetClaimed() {
        if (packetVerdict == 0) {
            packetVerdict = claimedByFeature(packet, packetSlot) ? 1 : 2;
        }
        return packetVerdict == 1;
    }

    private static boolean claimedByFeature(ClientboundLevelParticlesPacket p, int slot) {
        if (p == null) {
            return false;
        }
        try {
            if (BurrowDetector.getInstance().matchesSignature(typeId(slot), p)) {
                return true;
            }
            if (TreasureChestTracker.getInstance().claimsPacket(p)) {
                return true;
            }
            return PrecisionMiningTracker.LISTENING && PrecisionMiningTracker.getInstance().claimsPacket(p);
        } catch (RuntimeException e) {
            return true; // a feature check that throws must never cost the player its particles
        }
    }

    /** The registry id string for a slot, built once per type and then reused. */
    private static String typeId(int slot) {
        if (slot < 0 || slot >= typeIds.length) {
            return "";
        }
        String id = typeIds[slot];
        if (id == null) {
            Identifier key = BuiltInRegistries.PARTICLE_TYPE.getKey(BuiltInRegistries.PARTICLE_TYPE.byId(slot));
            id = key == null ? "" : key.toString();
            typeIds[slot] = id;
        }
        return id;
    }

    // ------------------------------------------------------------------ caps

    private static ParticleBudget ensureBudget() {
        if (budget == null) {
            int types = BuiltInRegistries.PARTICLE_TYPE.size();
            budget = new ParticleBudget(types + 1);
            blockBreakSlot = types;
            typeIds = new String[types];
            dirty = true;
        }
        if (dirty) {
            applyCaps(cfg());
            dirty = false;
        }
        return budget;
    }

    private static void applyCaps(SBSConfig.ParticleSettings s) {
        budget.clearCaps();
        Set<String> never = s.neverLimit;
        cap(ParticleTypes.EXPLOSION, s.capExplosion, never);
        cap(ParticleTypes.EXPLOSION_EMITTER, s.capExplosion, never);
        cap(ParticleTypes.CRIT, s.capCrit, never);
        cap(ParticleTypes.ENCHANTED_HIT, s.capCrit, never);
        cap(ParticleTypes.FIREWORK, s.capFirework, never);
        budget.setCap(blockBreakSlot, neverLimited(ParticleFilter.BLOCK_ID, never)
                ? ParticleBudget.UNLIMITED : s.capBlockBreak);
    }

    private static void cap(ParticleType<?> type, int cap, Set<String> never) {
        int slot = BuiltInRegistries.PARTICLE_TYPE.getId(type);
        Identifier key = BuiltInRegistries.PARTICLE_TYPE.getKey(type);
        if (key != null && neverLimited(key.toString(), never)) {
            return;
        }
        budget.setCap(slot, cap);
    }

    /** Matches namespaced and bare ids, like the per-type list does. */
    static boolean neverLimited(String namespacedId, Set<String> never) {
        if (never == null || never.isEmpty()) {
            return false;
        }
        String bare = namespacedId.startsWith("minecraft:") ? namespacedId.substring(10) : namespacedId;
        for (String id : never) {
            if (id == null) {
                continue;
            }
            String t = id.trim().toLowerCase(Locale.ROOT);
            if (t.equals(namespacedId) || t.equals(bare)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ perf

    /** "type n/s" for every slot that dropped anything in the last second, highest first. */
    public static String droppedSummary() {
        if (budget == null || !cfg().limitSpam) {
            return "off";
        }
        List<int[]> rows = new ArrayList<>();
        for (int i = 0; i < budget.size(); i++) {
            int n = budget.droppedPerSecond(i);
            if (n > 0) {
                rows.add(new int[] {i, n});
            }
        }
        if (rows.isEmpty()) {
            return "none";
        }
        rows.sort((a, b) -> Integer.compare(b[1], a[1]));
        StringBuilder out = new StringBuilder();
        for (int[] row : rows) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(row[0] == blockBreakSlot ? "block_break" : typeId(row[0]).replace("minecraft:", ""))
                    .append(' ').append(row[1]).append("/s");
        }
        return out.toString();
    }
}
