/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.treasurechest.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig.MiningHelpersSettings;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.helper.map.logic.HollowsTracker;
import sbs.modid.client.helper.timers.ServerWorldTime;
import sbs.modid.client.skills.mining.treasurechest.model.TreasureChestSignals;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The game-facing half of the treasure-chest helper: chat, block updates, particle packets and the
 * player's own clicks in; claimed chests, lockpick markers and the session count out.
 *
 * <p>Read-only by design. The mod never clicks the lock or turns the camera toward it - it draws a
 * box on the chest and a dot on the spot, and the player does the picking. The click this class
 * sees is the player's own, observed through {@code BlockInteractMixin}; it is used only to clear
 * the dot.
 *
 * <p>The pairing, the particle gate and the loot parse are pure classes beside this one
 * ({@link ChestClaim}, {@link LockpickSpot}, {@link ChestLootParser}, {@link ChestSession}) so they
 * can be tested; this class only decides when they run and what gets logged. Everything it assumes
 * about the game is in {@link TreasureChestSignals}, and none of it has been seen live - hence the
 * {@code [SBS][Chest]} lines, which are the probe.
 */
public final class TreasureChestTracker {

    private static final TreasureChestTracker INSTANCE = new TreasureChestTracker();

    /** The armed state is re-derived at this cadence - it reads the location, which walks lines. */
    private static final long ARM_CACHE_MS = 500L;
    /** Upper bound on distinct particle shapes logged per lobby. */
    private static final int MAX_SHAPES_LOGGED = 32;

    /** One chest the player uncovered. */
    public static final class Chest {
        private final int x;
        private final int y;
        private final int z;
        private final long claimedAt;
        private final LockpickSpot.Marker marker = new LockpickSpot.Marker();

        private Chest(int x, int y, int z, long claimedAt) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.claimedAt = claimedAt;
        }

        public int x() {
            return x;
        }

        public int y() {
            return y;
        }

        public int z() {
            return z;
        }

        /** The spot the lockpick burst marks right now, or {@code null}. */
        public LockpickSpot.Spot spot(long now) {
            return marker.current(now);
        }

        private boolean at(BlockPos pos) {
            return pos.getX() == x && pos.getY() == y && pos.getZ() == z;
        }
    }

    private final List<Chest> chests = new ArrayList<>();
    private final ChestClaim claim = new ChestClaim();
    private final ChestLootParser loot = new ChestLootParser();
    private final ChestSession session = new ChestSession();
    private final Set<String> shapesLogged = new HashSet<>();

    private boolean armed;
    private long armedAt = Long.MIN_VALUE / 2;
    private String lobby = "";

    private TreasureChestTracker() {
    }

    public static TreasureChestTracker getInstance() {
        return INSTANCE;
    }

    private static MiningHelpersSettings cfg() {
        return ConfigManager.getInstance().get().miningHelpers;
    }

    /**
     * Whether anything here should run: a row is on and the player is in the Crystal Hollows.
     * Cached, because the block-update and particle hooks ask on every packet. A lobby or island
     * change seen here drops every claimed chest - their coordinates belong to the old instance.
     */
    private boolean armed() {
        long now = System.currentTimeMillis();
        if (now - armedAt < ARM_CACHE_MS) {
            return armed;
        }
        armedAt = now;
        MiningHelpersSettings cfg = cfg();
        boolean on = cfg.enabled
                && (cfg.treasureChestBox || cfg.lockpickMarker || cfg.treasureChestCounter)
                && SkyBlockLocation.onIsland(HollowsTracker.ISLAND);
        String server = ServerWorldTime.serverName();
        if (!on || !server.equals(lobby)) {
            clearChests();
            lobby = server;
        }
        armed = on;
        return armed;
    }

    // ------------------------------------------------------------------ inputs

    /** From {@code BlockUpdateMixin}: every block the server changes. Returns on a boolean outside. */
    public void onBlockChanged(BlockPos pos, BlockState previous, BlockState next) {
        if (!armed()) {
            return;
        }
        boolean nowChest = next.getBlock() instanceof ChestBlock;
        boolean wasChest = previous.getBlock() instanceof ChestBlock;
        if (!nowChest) {
            removeAt(pos, "block became " + blockId(next));
            return;
        }
        if (wasChest) {
            return;
        }
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        Vec3 at = player.position();
        long now = System.currentTimeMillis();
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Chest] chest block appeared: {} at {} "
                        + "(offset from you {}, {}, {})",
                blockId(next), pos.toShortString(),
                fmt(pos.getX() + 0.5 - at.x), fmt(pos.getY() + 0.5 - at.y), fmt(pos.getZ() + 0.5 - at.z));
        ChestClaim.Appearance claimed =
                claim.onChestAppeared(pos.getX(), pos.getY(), pos.getZ(), now, at.x, at.y, at.z);
        if (claimed != null) {
            add(claimed, now, "block after the line");
        }
    }

    /** From {@code ChatPriceListenerMixin}: every displayed chat line. */
    public void onChat(String text) {
        if (text == null || !armed()) {
            return;
        }
        long now = System.currentTimeMillis();
        String line = ChestLootParser.strip(text);
        if (TreasureChestSignals.SPAWN.matcher(line).matches()) {
            onSpawnLine(line, now);
            return;
        }
        ChestLootParser.Event event = loot.onLine(text, now);
        switch (event.kind()) {
            case CHEST -> {
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Chest] reward header: \"{}\"", line);
                if (cfg().treasureChestCounter) {
                    session.chestOpened(now);
                }
            }
            case POWDER -> {
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Chest] reward powder: {} {}",
                        event.amount(), event.type());
                if (cfg().treasureChestCounter) {
                    session.powder(event.type(), event.amount(), now);
                }
            }
            case UNREAD -> SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][Chest] reward line unread (not powder, or a shape the parser does not "
                            + "know): \"{}\"", line);
            case NONE -> { }
        }
    }

    private void onSpawnLine(String line, long now) {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        Vec3 at = player.position();
        ChestClaim.Appearance claimed = claim.onSpawnLine(now, at.x, at.y, at.z);
        if (claimed != null) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Chest] spawn line \"{}\" paired with a chest "
                    + "that had already appeared", line);
            add(claimed, now, "block before the line");
        } else {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Chest] spawn line \"{}\" - waiting {} ms for "
                    + "its chest block", line, TreasureChestSignals.CLAIM_WINDOW_MS);
        }
    }

    /**
     * From {@code ParticleProbeMixin}: one particle packet, main thread. Only packets inside a
     * claimed chest's grown block reach the marker - another player's chest never does.
     */
    public void onParticlePacket(ClientboundLevelParticlesPacket packet) {
        if (packet == null || chests.isEmpty() || !armed() || !cfg().lockpickMarker) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Chest chest : chests) {
            LockpickSpot.Spot spot = LockpickSpot.locate(chest.x, chest.y, chest.z,
                    packet.getX(), packet.getY(), packet.getZ());
            if (spot == null) {
                continue;
            }
            chest.marker.onBurst(spot, now);
            logShape(packet, spot, chest);
            return;
        }
    }

    /** From {@code BlockInteractMixin}: the player right-clicked {@code pos}. Only clears a marker. */
    public void onBlockClicked(BlockPos pos) {
        if (pos == null || chests.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Chest chest : chests) {
            if (chest.at(pos)) {
                chest.marker.onClick(now);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ state

    /**
     * The chests to draw. Also where time-based upkeep happens - the render calls it every frame,
     * and the upkeep is two comparisons per chest.
     */
    public List<Chest> chests() {
        if (!armed()) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        if (claim.pendingExpired(now)) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Chest] unclaimed spawn line: no chest block "
                    + "appeared within {} blocks and {} ms - the chest may not be a chest block",
                    TreasureChestSignals.CLAIM_RADIUS, TreasureChestSignals.CLAIM_WINDOW_MS);
        }
        Iterator<Chest> it = chests.iterator();
        while (it.hasNext()) {
            if (now - it.next().claimedAt > TreasureChestSignals.CHEST_LIFETIME_MS) {
                it.remove();
            }
        }
        return chests;
    }

    public ChestSession session() {
        return session;
    }

    /** The "Reset Session Gain" button. */
    public void resetSession() {
        session.reset();
    }

    /** World change, server hop: nothing here describes the next instance. */
    public void onWorldChange() {
        clearChests();
        session.reset();
        lobby = "";
        armedAt = Long.MIN_VALUE / 2;
    }

    private void add(ChestClaim.Appearance appearance, long now, String how) {
        for (Chest chest : chests) {
            if (chest.x == appearance.x() && chest.y == appearance.y() && chest.z == appearance.z()) {
                return;
            }
        }
        if (chests.size() >= TreasureChestSignals.MAX_CHESTS) {
            chests.remove(0);
        }
        chests.add(new Chest(appearance.x(), appearance.y(), appearance.z(), now));
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Chest] claimed chest at {} {} {} ({})",
                appearance.x(), appearance.y(), appearance.z(), how);
    }

    private void removeAt(BlockPos pos, String why) {
        if (chests.isEmpty()) {
            return;
        }
        Iterator<Chest> it = chests.iterator();
        while (it.hasNext()) {
            Chest chest = it.next();
            if (chest.at(pos)) {
                it.remove();
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Chest] chest at {} gone: {}",
                        pos.toShortString(), why);
                return;
            }
        }
    }

    private void clearChests() {
        chests.clear();
        claim.clear();
        loot.clear();
        shapesLogged.clear();
    }

    /**
     * One line per distinct packet shape seen inside a claimed chest - the lockpick half of the
     * probe. Which shape is the lock is unknown, and this is what says so.
     */
    private void logShape(ClientboundLevelParticlesPacket packet, LockpickSpot.Spot spot, Chest chest) {
        String type = typeId(packet.getParticle());
        String shape = String.format(Locale.ROOT, "%s n=%d off=(%.2f,%.2f,%.2f) spd=%.3f",
                type, packet.getCount(), packet.getXDist(), packet.getYDist(), packet.getZDist(),
                packet.getMaxSpeed());
        if (shapesLogged.size() >= MAX_SHAPES_LOGGED || !shapesLogged.add(shape)) {
            return;
        }
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Chest] particle in chest box: {} on the {} face, "
                        + "at ({}, {}, {}) relative to the chest's corner",
                shape, spot.face(), fmt(spot.x() - chest.x), fmt(spot.y() - chest.y),
                fmt(spot.z() - chest.z));
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
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
