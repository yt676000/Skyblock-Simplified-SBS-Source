/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.logic;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.skills.mining.model.EffectiveBlockTable;
import sbs.modid.client.skills.mining.model.EffectiveBlockTable.Entry;
import sbs.modid.client.skills.mining.model.EffectiveBlockTable.Ore;
import sbs.modid.client.skills.mining.model.EffectiveBlockTable.Tier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Finds the Mithril, Umber and Tungsten blocks around the player that the player can actually
 * see, for {@code EffectiveBlockHighlight} to tint.
 *
 * <p><b>Visible blocks only - this is not x-ray.</b> Showing ore through walls was rejected
 * (IDEAS.md, "Ore and gemstone x-ray"). Two rules keep it that way, and both are applied before
 * anything reaches the renderer:
 * <ol>
 *   <li>a block is a candidate only with at least one face touching air, and only those exposed
 *       faces are ever drawn;</li>
 *   <li>an exposed face is drawn only when a line from the camera to that face's centre crosses no
 *       occluding block. The world overlay projects onto the HUD and has no depth buffer to test
 *       against ({@code core/render/WorldRender}), so this line test is what stands in for the
 *       depth test - the same idea as the trap and waypoint renderers, done as a voxel walk so a
 *       few hundred faces stay cheap.</li>
 * </ol>
 *
 * <p><b>Cost.</b> The cube around the player is swept a slab at a time so a whole pass is spread
 * over {@link #SWEEP_TICKS} ticks, and block-change packets re-check only the changed block and its
 * neighbours. Visibility is re-tested every tick, nearest first, up to {@link #MAX_RAYS} faces. A
 * mined block turns into bedrock (or titanium), which is not in the table, so it drops out on the
 * next check by itself.
 *
 * <p><b>Display only.</b> Nothing here aims, selects or cycles a target; it tints blocks the player
 * is already looking at.
 */
public final class EffectiveBlockScanner {

    /** Ticks one full sweep of the cube is spread over. */
    public static final int SWEEP_TICKS = 10;
    /** Most candidates kept; the rest of a pathological cube is ignored and the log says so. */
    public static final int MAX_CANDIDATES = 2000;
    /** Most faces line-tested per tick; the farthest are the ones left out. */
    public static final int MAX_RAYS = 1500;
    /** Most changed positions queued between ticks before a full sweep is asked for instead. */
    private static final int MAX_PENDING = 256;

    /** Face offsets, indexed like {@link net.minecraft.core.Direction#ordinal()}: D U N S W E. */
    public static final int[] FACE_DX = {0, 0, 0, 0, -1, 1};
    public static final int[] FACE_DY = {-1, 1, 0, 0, 0, 0};
    public static final int[] FACE_DZ = {0, 0, -1, 1, 0, 0};

    /** Whether the block at a position is air. */
    @FunctionalInterface
    public interface AirTest {
        boolean isAir(int x, int y, int z);
    }

    /** Whether the block at a position hides what is behind it. */
    @FunctionalInterface
    public interface OpaqueTest {
        boolean isOpaque(int x, int y, int z);
    }

    /** One ore block that touches air. */
    public record Candidate(long pos, Block block, Entry entry, int faces) {
    }

    private static final EffectiveBlockScanner INSTANCE = new EffectiveBlockScanner();

    /** What each block type is in the table, learned once per block type; NONE when it is not. */
    private final Map<Block, Entry> entryByBlock = new IdentityHashMap<>();
    private final Set<Block> lookAlikeBlocks = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Entry NONE = new Entry("", Ore.MITHRIL, 0, 1, false, false,
            sbs.modid.client.helper.rift.model.Certainty.UNKNOWN);

    // The live candidate set, sorted nearest-first from where its sweep started.
    private Map<Long, Candidate> candidates = new HashMap<>();
    private List<Candidate> ordered = List.of();
    private boolean orderDirty;

    // The sweep in progress.
    private Map<Long, Candidate> building = new HashMap<>();
    private BlockPos sweepCentre;
    private int sweepRadius;
    private int sweepNextX;
    private boolean sweepCapped;
    private final Set<String> sweepIds = new TreeSet<>();
    private final Set<String> sweepLookAlikes = new TreeSet<>();

    private final java.util.ArrayDeque<Long> pending = new java.util.ArrayDeque<>();
    private boolean fullRescan;

    // Where the gate last armed, and the tiers / allowed blocks for that place and config.
    private String armedKey = "";
    private boolean armedLogged;
    private ClientLevel armedLevel;
    private Map<Entry, Tier> active = Map.of();
    private int activeConfigHash;
    private boolean armed;

    // What the renderer draws: one exposed, visible face each, nearest first.
    private VisibleFaces visible = VisibleFaces.EMPTY;

    private EffectiveBlockScanner() {
    }

    public static EffectiveBlockScanner getInstance() {
        return INSTANCE;
    }

    /** Whether the overlay is switched on and the player is somewhere one of its ores is mined. */
    public boolean armed() {
        return armed;
    }

    /** The faces to tint this frame. Never null. */
    public VisibleFaces visible() {
        return visible;
    }

    /** The tier of an entry under the current settings, or {@code null} when it is not active. */
    public Tier tierOf(Entry entry) {
        return active.get(entry);
    }

    // ------------------------------------------------------------------ the tick

    /**
     * Re-arms on a zone change, applies queued block changes, and re-tests visibility. Every tick:
     * a stale visibility answer is what would let a tint show through a wall you just walked
     * behind.
     */
    public void onClientTick() {
        SBSConfig.MiningHelpersSettings cfg = ConfigManager.getInstance().get().miningHelpers;
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (!cfg.effectiveBlocks || player == null || level == null) {
            disarm();
            return;
        }
        String island = SkyBlockLocation.island();
        String zone = SkyBlockLocation.zone();
        Set<Ore> ores = enabledOres(cfg, island, zone);
        if (ores.isEmpty()) {
            disarm();
            return;
        }
        String key = island + "/" + zone;
        int configHash = configHash(cfg);
        if (!key.equals(armedKey) || level != armedLevel || configHash != activeConfigHash) {
            boolean placeChanged = !key.equals(armedKey) || level != armedLevel;
            rearm(cfg, island, zone, key, level, configHash, placeChanged);
        }
        armed = true;

        applyPending(level);
        if (orderDirty) {
            reorder();
        }
        Camera camera = minecraft.gameRenderer.mainCamera();
        visible = testVisibility(level, camera.position(), camera.forwardVector());
    }

    /**
     * Advances the sweep by one slice. Separate from {@link #onClientTick} so the performance
     * budget may defer it: a late sweep only means a newly exposed block appears a little later.
     */
    public void onSweepTick() {
        if (!armed) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null) {
            return;
        }
        if (sweepCentre == null || fullRescan) {
            startSweep(player.blockPosition());
            fullRescan = false;
        }
        int width = sweepRadius * 2 + 1;
        int slices = Math.max(1, (width + SWEEP_TICKS - 1) / SWEEP_TICKS);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int i = 0; i < slices && sweepNextX <= sweepRadius; i++, sweepNextX++) {
            sweepSlice(level, sweepNextX, cursor);
        }
        if (sweepNextX > sweepRadius) {
            finishSweep(level);
            startSweep(player.blockPosition());
        }
    }

    /**
     * A block the server changed, seen before it is applied. Only queued here: the new state is
     * read on the next tick, when the level holds it.
     */
    public void onBlockChanged(BlockPos pos) {
        if (!armed || sweepCentre == null) {
            return;
        }
        if (Math.abs(pos.getX() - sweepCentre.getX()) > sweepRadius + 1
                || Math.abs(pos.getY() - sweepCentre.getY()) > sweepRadius + 1
                || Math.abs(pos.getZ() - sweepCentre.getZ()) > sweepRadius + 1) {
            return;
        }
        if (pending.size() >= MAX_PENDING) {
            fullRescan = true;
            return;
        }
        pending.add(pos.asLong());
    }

    // ------------------------------------------------------------------ arming

    private void rearm(SBSConfig.MiningHelpersSettings cfg, String island, String zone, String key,
                       ClientLevel level, int configHash, boolean placeChanged) {
        Map<String, Tier> tiers = EffectiveBlockTable.tiers(cfg.effectiveInstaMineSoft,
                cfg.effectiveHalfBlocks);
        Set<Ore> ores = enabledOres(cfg, island, zone);
        Map<Entry, Tier> next = new IdentityHashMap<>();
        for (Entry entry : EffectiveBlockTable.entries()) {
            Tier tier = tiers.get(entry.blockId());
            if (tier != null && ores.contains(entry.ore())
                    && EffectiveBlockTable.allowedHere(entry, island, zone)) {
                next.put(entry, tier);
            }
        }
        active = next;
        activeConfigHash = configHash;
        if (placeChanged) {
            armedKey = key;
            armedLevel = level;
            armedLogged = false;
            clearBlocks();
        } else {
            fullRescan = true;     // a toggle changed which blocks count; look again from scratch
        }
    }

    private void disarm() {
        if (!armed && armedKey.isEmpty()) {
            return;
        }
        armed = false;
        armedKey = "";
        armedLevel = null;
        active = Map.of();
        clearBlocks();
    }

    private void clearBlocks() {
        candidates = new HashMap<>();
        ordered = List.of();
        building = new HashMap<>();
        sweepCentre = null;
        pending.clear();
        visible = VisibleFaces.EMPTY;
    }

    private static Set<Ore> enabledOres(SBSConfig.MiningHelpersSettings cfg, String island, String zone) {
        Set<Ore> ores = EnumSet.noneOf(Ore.class);
        for (Ore ore : EffectiveBlockTable.oresHere(island, zone)) {
            boolean on = switch (ore) {
                case MITHRIL -> cfg.effectiveMithril;
                case UMBER -> cfg.effectiveUmber;
                case TUNGSTEN -> cfg.effectiveTungsten;
            };
            if (on) {
                ores.add(ore);
            }
        }
        return ores;
    }

    private static int configHash(SBSConfig.MiningHelpersSettings cfg) {
        return java.util.Objects.hash(cfg.effectiveMithril, cfg.effectiveUmber, cfg.effectiveTungsten,
                cfg.effectiveHalfBlocks, cfg.effectiveInstaMineSoft, radius(cfg));
    }

    private static int radius(SBSConfig.MiningHelpersSettings cfg) {
        return Math.max(1, Math.min(24, cfg.effectiveRadius));
    }

    // ------------------------------------------------------------------ the sweep

    private void startSweep(BlockPos centre) {
        sweepCentre = centre.immutable();
        sweepRadius = radius(ConfigManager.getInstance().get().miningHelpers);
        sweepNextX = -sweepRadius;
        sweepCapped = false;
        building = new HashMap<>();
        sweepIds.clear();
        sweepLookAlikes.clear();
    }

    private void sweepSlice(ClientLevel level, int dx, BlockPos.MutableBlockPos cursor) {
        int r = sweepRadius;
        int x = sweepCentre.getX() + dx;
        for (int dy = -r; dy <= r; dy++) {
            int y = sweepCentre.getY() + dy;
            for (int dz = -r; dz <= r; dz++) {
                int z = sweepCentre.getZ() + dz;
                BlockState state = level.getBlockState(cursor.set(x, y, z));
                Entry entry = entryOf(state.getBlock(), true);
                if (entry == null) {
                    continue;
                }
                sweepIds.add(entry.blockId());
                if (building.size() >= MAX_CANDIDATES) {
                    sweepCapped = true;
                    continue;
                }
                Candidate candidate = evaluate(level, x, y, z, state.getBlock(), entry, cursor);
                if (candidate != null) {
                    building.put(candidate.pos(), candidate);
                }
            }
        }
    }

    private void finishSweep(ClientLevel level) {
        candidates = building;
        building = new HashMap<>();
        orderDirty = true;
        if (!armedLogged) {
            armedLogged = true;
            List<String> ores = new ArrayList<>();
            for (Entry entry : active.keySet()) {
                if (!ores.contains(entry.ore().displayName())) {
                    ores.add(entry.ore().displayName());
                }
            }
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][EffectiveBlocks] armed in {}, ores={}, {} candidates{}, "
                            + "block ids seen: {}, unlisted look-alikes: {}",
                    armedKey, ores, candidates.size(), sweepCapped ? " (capped)" : "",
                    sweepIds, sweepLookAlikes);
        }
    }

    /** The active entry for a block type, or {@code null}. Learns each block type once. */
    private Entry entryOf(Block block, boolean noteLookAlikes) {
        Entry entry = entryByBlock.get(block);
        if (entry == null) {
            String id = BuiltInRegistries.BLOCK.getKey(block).getPath();
            Entry found = EffectiveBlockTable.lookup(id);
            entry = found == null ? NONE : found;
            entryByBlock.put(block, entry);
            if (EffectiveBlockTable.looksLikeOre(id)) {
                lookAlikeBlocks.add(block);
            }
        }
        if (entry == NONE) {
            if (noteLookAlikes && !lookAlikeBlocks.isEmpty() && lookAlikeBlocks.contains(block)) {
                sweepLookAlikes.add(BuiltInRegistries.BLOCK.getKey(block).getPath());
            }
            return null;
        }
        return active.containsKey(entry) ? entry : null;
    }

    /** The candidate at a position, or {@code null} when it is buried. */
    private static Candidate evaluate(ClientLevel level, int x, int y, int z, Block block, Entry entry,
                                      BlockPos.MutableBlockPos cursor) {
        int faces = exposedFaces(x, y, z, (ax, ay, az) -> level.getBlockState(cursor.set(ax, ay, az)).isAir());
        return faces == 0 ? null : new Candidate(BlockPos.asLong(x, y, z), block, entry, faces);
    }

    private void applyPending(ClientLevel level) {
        if (pending.isEmpty()) {
            return;
        }
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        while (!pending.isEmpty()) {
            long packed = pending.poll();
            int x = BlockPos.getX(packed);
            int y = BlockPos.getY(packed);
            int z = BlockPos.getZ(packed);
            recheck(level, x, y, z, cursor);
            for (int f = 0; f < 6; f++) {
                recheck(level, x + FACE_DX[f], y + FACE_DY[f], z + FACE_DZ[f], cursor);
            }
        }
        orderDirty = true;
    }

    private void recheck(ClientLevel level, int x, int y, int z, BlockPos.MutableBlockPos cursor) {
        long key = BlockPos.asLong(x, y, z);
        Block block = level.getBlockState(cursor.set(x, y, z)).getBlock();
        Entry entry = entryOf(block, false);
        Candidate candidate = entry == null ? null : evaluate(level, x, y, z, block, entry, cursor);
        if (candidate == null) {
            candidates.remove(key);
        } else if (candidates.size() < MAX_CANDIDATES || candidates.containsKey(key)) {
            candidates.put(key, candidate);
        }
    }

    private void reorder() {
        orderDirty = false;
        if (sweepCentre == null) {
            ordered = new ArrayList<>(candidates.values());
            return;
        }
        long cx = sweepCentre.getX();
        long cy = sweepCentre.getY();
        long cz = sweepCentre.getZ();
        List<Candidate> list = new ArrayList<>(candidates.values());
        list.sort(Comparator.comparingLong(c -> {
            long dx = BlockPos.getX(c.pos()) - cx;
            long dy = BlockPos.getY(c.pos()) - cy;
            long dz = BlockPos.getZ(c.pos()) - cz;
            return dx * dx + dy * dy + dz * dz;
        }));
        ordered = list;
    }

    // ------------------------------------------------------------------ visibility

    private VisibleFaces testVisibility(ClientLevel level, Vec3 eye, Vector3fc forward) {
        List<Candidate> list = ordered;
        if (list.isEmpty()) {
            return VisibleFaces.EMPTY;
        }
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        OpaqueTest opaque = (x, y, z) -> level.getBlockState(cursor.set(x, y, z)).canOcclude();
        VisibleFaces.Builder out = new VisibleFaces.Builder();
        int rays = 0;
        for (Candidate candidate : list) {
            int bx = BlockPos.getX(candidate.pos());
            int by = BlockPos.getY(candidate.pos());
            int bz = BlockPos.getZ(candidate.pos());
            for (int f = 0; f < 6; f++) {
                if ((candidate.faces() & (1 << f)) == 0) {
                    continue;
                }
                // The face's centre, nudged out into the air block in front of it.
                double fx = bx + 0.5 + FACE_DX[f] * 0.52;
                double fy = by + 0.5 + FACE_DY[f] * 0.52;
                double fz = bz + 0.5 + FACE_DZ[f] * 0.52;
                double vx = eye.x - fx;
                double vy = eye.y - fy;
                double vz = eye.z - fz;
                if (vx * FACE_DX[f] + vy * FACE_DY[f] + vz * FACE_DZ[f] <= 0) {
                    continue;                 // faces away from the camera
                }
                if (-(vx * forward.x() + vy * forward.y() + vz * forward.z()) <= 0) {
                    continue;                 // behind the camera
                }
                if (rays >= MAX_RAYS) {
                    return out.build();
                }
                rays++;
                if (lineClear(eye.x, eye.y, eye.z, fx, fy, fz, opaque)) {
                    out.add(candidate, f);
                }
            }
        }
        return out.build();
    }

    // ------------------------------------------------------------------ the pure rules

    /**
     * The faces of the block at (x, y, z) that touch air, as a bitmask indexed like
     * {@link net.minecraft.core.Direction#ordinal()}. Zero means the block is buried and is not a
     * candidate at all.
     */
    public static int exposedFaces(int x, int y, int z, AirTest air) {
        int faces = 0;
        for (int f = 0; f < 6; f++) {
            if (air.isAir(x + FACE_DX[f], y + FACE_DY[f], z + FACE_DZ[f])) {
                faces |= 1 << f;
            }
        }
        return faces;
    }

    /**
     * Whether the straight line from {@code (ox, oy, oz)} to {@code (tx, ty, tz)} crosses no opaque
     * block. Walks every voxel the segment passes through (Amanatides and Woo); the voxel the line
     * starts in is skipped, so a camera clipped into a block does not blind itself, and so is the
     * voxel it ends in, which for a face test is the air in front of that face.
     */
    public static boolean lineClear(double ox, double oy, double oz, double tx, double ty, double tz,
                                    OpaqueTest opaque) {
        int x = floor(ox);
        int y = floor(oy);
        int z = floor(oz);
        int endX = floor(tx);
        int endY = floor(ty);
        int endZ = floor(tz);
        double dx = tx - ox;
        double dy = ty - oy;
        double dz = tz - oz;
        int stepX = Double.compare(dx, 0);
        int stepY = Double.compare(dy, 0);
        int stepZ = Double.compare(dz, 0);
        double tDeltaX = stepX == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dx);
        double tDeltaY = stepY == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dy);
        double tDeltaZ = stepZ == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dz);
        double tMaxX = stepX == 0 ? Double.MAX_VALUE : (stepX > 0 ? (x + 1 - ox) : (ox - x)) * tDeltaX;
        double tMaxY = stepY == 0 ? Double.MAX_VALUE : (stepY > 0 ? (y + 1 - oy) : (oy - y)) * tDeltaY;
        double tMaxZ = stepZ == 0 ? Double.MAX_VALUE : (stepZ > 0 ? (z + 1 - oz) : (oz - z)) * tDeltaZ;
        // A segment crosses at most |dx|+|dy|+|dz|+3 voxels; the bound keeps a float edge case
        // from ever looping.
        int limit = (int) (Math.abs(dx) + Math.abs(dy) + Math.abs(dz)) + 3;
        for (int i = 0; i < limit; i++) {
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                if (tMaxX > 1.0) {
                    break;
                }
                x += stepX;
                tMaxX += tDeltaX;
            } else if (tMaxY < tMaxZ) {
                if (tMaxY > 1.0) {
                    break;
                }
                y += stepY;
                tMaxY += tDeltaY;
            } else {
                if (tMaxZ > 1.0) {
                    break;
                }
                z += stepZ;
                tMaxZ += tDeltaZ;
            }
            if (x == endX && y == endY && z == endZ) {
                return true;
            }
            if (opaque.isOpaque(x, y, z)) {
                return false;
            }
        }
        return true;
    }

    private static int floor(double value) {
        int i = (int) value;
        return value < i ? i - 1 : i;
    }

    // ------------------------------------------------------------------ the output

    /** The faces to tint, packed into parallel arrays so a frame allocates nothing to read them. */
    public static final class VisibleFaces {

        static final VisibleFaces EMPTY = new VisibleFaces(new Candidate[0], new byte[0], 0);

        private final Candidate[] candidates;
        private final byte[] faces;
        private final int size;

        private VisibleFaces(Candidate[] candidates, byte[] faces, int size) {
            this.candidates = candidates;
            this.faces = faces;
            this.size = size;
        }

        public int size() {
            return size;
        }

        public Candidate candidate(int i) {
            return candidates[i];
        }

        /** The face, as a {@link net.minecraft.core.Direction#ordinal()}. */
        public int face(int i) {
            return faces[i];
        }

        static final class Builder {
            private Candidate[] candidates = new Candidate[64];
            private byte[] faces = new byte[64];
            private int size;

            void add(Candidate candidate, int face) {
                if (size == candidates.length) {
                    candidates = java.util.Arrays.copyOf(candidates, size * 2);
                    faces = java.util.Arrays.copyOf(faces, size * 2);
                }
                candidates[size] = candidate;
                faces[size] = (byte) face;
                size++;
            }

            VisibleFaces build() {
                return size == 0 ? EMPTY : new VisibleFaces(candidates, faces, size);
            }
        }
    }
}
