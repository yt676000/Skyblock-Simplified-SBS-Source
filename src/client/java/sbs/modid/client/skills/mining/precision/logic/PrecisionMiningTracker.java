/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.precision.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig.MiningHelpersSettings;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.helper.timers.ServerWorldTime;
import sbs.modid.client.skills.SkillIslands;
import sbs.modid.client.skills.mining.logic.HotmTreeStore;
import sbs.modid.client.skills.mining.precision.model.PrecisionSignals;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The game-facing half of the Precision Mining target: the block the player is mining and the
 * particle packets around it in, the current target out. Also the probe that finds out what the
 * target actually is.
 *
 * <p><b>Which block.</b> Only the one the player is breaking right now, read from the client's own
 * state - {@code MultiPlayerGameMode.isDestroying()} and the crosshair's block hit. Particles on any
 * other block are never matched, so nothing is ever marked on a block the player is not mining.
 *
 * <p><b>Display only.</b> This class reads; it never turns the camera, nudges the aim or sends
 * input. The player moves the mouse - the overlay only says where the target is and whether the
 * crosshair is on it.
 *
 * <p><b>The probe</b> runs whenever the player mines on a mining island with Mining Helpers on,
 * whether or not the overlay is switched on, because the overlay ships off until the probe has
 * answered its questions. It writes {@code [SBS][Precision]} lines: each new particle shape near
 * the mined block, and one summary per block saying whether a target was found. Both are capped per
 * lobby. The matching rules themselves live in {@link PrecisionTarget} and {@link PrecisionSignals}.
 */
public final class PrecisionMiningTracker {

    private static final PrecisionMiningTracker INSTANCE = new PrecisionMiningTracker();

    /**
     * True while a mined block is tracked. Read by {@code ParticleProbeMixin} on every particle
     * packet, so the cost while the player is not mining is one static field read.
     */
    public static volatile boolean LISTENING;

    /** The armed state is re-derived at this cadence - it reads the location, which walks lines. */
    private static final long ARM_CACHE_MS = 500L;
    /** Stop tracking a block this long after the last tick it was being broken. */
    private static final long IDLE_MS = 3_000L;
    /** Particles arriving this long after the last breaking tick still belong to the block. */
    private static final long PARTICLE_WINDOW_MS = 1_000L;

    /** New particle shapes logged per block, and per lobby in total. */
    private static final int MAX_SHAPES_PER_BLOCK = 6;
    private static final int MAX_SHAPE_LINES = 200;
    /** Block summaries logged per (block id, target seen) pair, per lobby. */
    private static final int MAX_SUMMARIES_PER_KIND = 5;

    private final PrecisionTarget target = new PrecisionTarget();

    private long lastDestroyAt;
    private String blockName = "";
    private boolean armed;
    private long armedAt = Long.MIN_VALUE / 2;
    private String lobby = "";

    private final Set<String> blockShapes = new HashSet<>();
    private int shapeLines;
    private final Map<String, Integer> summaries = new HashMap<>();

    private PrecisionMiningTracker() {
    }

    public static PrecisionMiningTracker getInstance() {
        return INSTANCE;
    }

    private static MiningHelpersSettings cfg() {
        return ConfigManager.getInstance().get().miningHelpers;
    }

    /**
     * Whether anything here runs: Mining Helpers is on and the player is on a mining island. Not tied
     * to the overlay toggle - the probe has to run while the overlay ships off. A lobby change drops
     * the block and resets the log caps.
     */
    private boolean armed() {
        long now = System.currentTimeMillis();
        if (now - armedAt < ARM_CACHE_MS) {
            return armed;
        }
        armedAt = now;
        boolean on = cfg().enabled && SkillIslands.onMiningIsland();
        String server = ServerWorldTime.serverName();
        if (!on || !server.equals(lobby)) {
            finishBlock(on ? "lobby changed" : "left the mining islands");
            lobby = server;
            shapeLines = 0;
            summaries.clear();
        }
        armed = on;
        return armed;
    }

    // ------------------------------------------------------------------ inputs

    /** Once per client tick: which block is being broken, and whether the tracked one changed. */
    public void onClientTick() {
        if (!armed()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        MultiPlayerGameMode gameMode = minecraft.gameMode;
        ClientLevel level = minecraft.level;
        if (gameMode == null || level == null || minecraft.player == null) {
            return;
        }
        long now = System.currentTimeMillis();

        PrecisionTarget.MinedBlock tracked = target.block();
        if (tracked != null) {
            BlockState state = level.getBlockState(new BlockPos(tracked.x(), tracked.y(), tracked.z()));
            if (Block.getId(state) != tracked.stateId()) {
                finishBlock("block became " + blockId(state));
                tracked = null;
            }
        }

        if (gameMode.isDestroying() && minecraft.hitResult instanceof BlockHitResult hit
                && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = hit.getBlockPos();
            BlockState state = level.getBlockState(pos);
            lastDestroyAt = now;
            int stateId = Block.getId(state);
            if (tracked == null || tracked.x() != pos.getX() || tracked.y() != pos.getY()
                    || tracked.z() != pos.getZ() || tracked.stateId() != stateId) {
                if (tracked != null) {
                    finishBlock("moved to another block");
                }
                target.onMinedBlock(new PrecisionTarget.MinedBlock(pos.getX(), pos.getY(), pos.getZ(), stateId));
                blockName = blockId(state);
            }
        } else if (tracked != null && now - lastDestroyAt > IDLE_MS) {
            finishBlock("stopped mining");
        }
        LISTENING = target.block() != null;
    }

    /** From {@code ParticleProbeMixin}, main thread, only while {@link #LISTENING}. */
    public void onParticlePacket(ClientboundLevelParticlesPacket packet) {
        PrecisionTarget.MinedBlock block = target.block();
        if (packet == null || block == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastDestroyAt > PARTICLE_WINDOW_MS) {
            return;
        }
        double dx = packet.getX() - (block.x() + 0.5);
        double dy = packet.getY() - (block.y() + 0.5);
        double dz = packet.getZ() - (block.z() + 0.5);
        double reach = PrecisionSignals.PROBE_RADIUS;
        if (dx * dx + dy * dy + dz * dz > reach * reach) {
            return;
        }
        String type = typeId(packet.getParticle());
        logShape(packet, type, block, dx, dy, dz);
        target.onParticle(type, packet.getCount(), packet.getX(), packet.getY(), packet.getZ(), now);
    }

    /** World change, server hop: the block and the target belong to the old instance. */
    public void onWorldChange() {
        finishBlock("world changed");
        lobby = "";
        armedAt = Long.MIN_VALUE / 2;
        shapeLines = 0;
        summaries.clear();
    }

    // ------------------------------------------------------------------ state

    /**
     * The target to draw now, or {@code null}. {@code null} as well when the HotM tree has been read
     * and Precision Mining is not unlocked; an unread tree fails open, since the marker only ever
     * appears on a particle that is there.
     */
    public PrecisionTarget.Point current(long now) {
        if (!armed || perkState() == PerkState.LOCKED) {
            return null;
        }
        return target.current(now);
    }

    /** What the cached Heart of the Mountain tree says about the perk. */
    public enum PerkState { UNLOCKED, LOCKED, UNKNOWN }

    /**
     * Unlocked or locked from the tree cache. Whether the perk is toggled on or off in the menu is
     * not cached - the tree records an owned single-level perk as level 1 either way - so a perk
     * switched off reads as unlocked, and the overlay simply finds no particle.
     */
    public static PerkState perkState() {
        HotmTreeStore store = HotmTreeStore.getInstance();
        if (!store.known()) {
            return PerkState.UNKNOWN;
        }
        return store.level(PrecisionSignals.PERK_ID) > 0 ? PerkState.UNLOCKED : PerkState.LOCKED;
    }

    // ------------------------------------------------------------------ probe

    /**
     * Ends the tracked block: logs its summary - which block, and whether a target turned up - and
     * drops it. Capped per kind, so a long session still shows which ores get a target without one
     * line per block mined.
     */
    private void finishBlock(String why) {
        PrecisionTarget.MinedBlock block = target.block();
        if (block != null) {
            boolean seen = target.everSeen();
            String kind = blockName + (seen ? "|yes" : "|no");
            int count = summaries.merge(kind, 1, Integer::sum);
            if (count <= MAX_SUMMARIES_PER_KIND) {
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Precision] block {} at {} {} {} -> target seen: {}"
                                + "{} (perk {}, {}, {}){}",
                        blockName, block.x(), block.y(), block.z(), seen ? "yes" : "no",
                        seen ? " type=" + target.lockedType() : "",
                        perkState().name().toLowerCase(Locale.ROOT), SkyBlockLocation.describe(), why,
                        count == MAX_SUMMARIES_PER_KIND ? " - further " + kind + " summaries muted this lobby" : "");
            }
        }
        target.clear();
        blockShapes.clear();
        blockName = "";
        LISTENING = false;
    }

    /** One line per new type/count/offset/speed on the current block, capped per block and per lobby. */
    private void logShape(ClientboundLevelParticlesPacket packet, String type,
                          PrecisionTarget.MinedBlock block, double dx, double dy, double dz) {
        if (shapeLines >= MAX_SHAPE_LINES || blockShapes.size() >= MAX_SHAPES_PER_BLOCK) {
            return;
        }
        String shape = String.format(Locale.ROOT, "%s count=%d speed=%.3f off=%.2f,%.2f,%.2f",
                type, packet.getCount(), packet.getMaxSpeed(),
                packet.getXDist(), packet.getYDist(), packet.getZDist());
        if (!blockShapes.add(shape)) {
            return;
        }
        shapeLines++;
        PrecisionTarget.Face face = PrecisionTarget.nearestFace(block.x(), block.y(), block.z(),
                packet.getX(), packet.getY(), packet.getZ());
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Precision] particle {} at {},{},{} from block centre "
                        + "face={} (block {}){}",
                shape, fmt(dx), fmt(dy), fmt(dz), face, blockName,
                shapeLines == MAX_SHAPE_LINES ? " - particle lines muted for this lobby" : "");
    }

    private static String typeId(ParticleOptions options) {
        if (options == null) {
            return "null";
        }
        Identifier key = BuiltInRegistries.PARTICLE_TYPE.getKey(options.getType());
        return key != null ? key.toString() : options.getType().toString();
    }

    private static String blockId(BlockState state) {
        Identifier key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return key != null ? key.toString() : state.toString();
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
