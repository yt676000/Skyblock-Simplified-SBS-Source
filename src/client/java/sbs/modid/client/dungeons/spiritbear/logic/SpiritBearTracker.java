/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.spiritbear.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.combat.mobhighlight.logic.MobHighlightTracker;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.item.SkyblockItem;
import sbs.modid.client.dungeons.events.DungeonAlert;
import sbs.modid.client.dungeons.events.DungeonEvents;
import sbs.modid.client.dungeons.run.logic.DungeonStateManager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Thorn's Spirit Bear (F4 / M4 boss room): reads the arena's lantern ring, counts down the bear's
 * arrival from the moment the ring completes, and calls it out when it lands.
 *
 * <p><b>The ring is the counter, so the ring is what we read.</b> Thorn's arena carries a ring of
 * slots by the audience wall; every spirit animal killed lights one of them, and the bear spawns when
 * the last one lights. An unlit slot is a coal block and a lit one is a sea lantern, which makes the
 * whole thing readable from the world without knowing the floor's kill requirement at all - the ring
 * states its own size, so F4's smaller ring and M4's larger one both work with no table to maintain
 * and nothing to correct when Hypixel retunes the numbers.
 *
 * <p><b>Found by shape, not by coordinates.</b> The arena is scanned once per fight: a coarse
 * stride-{@value #SEED_STRIDE} sweep for any lantern-or-coal block, then a flood fill from each hit,
 * keeping clusters that are ring-sized and <b>flat</b> - a ring is a wall decoration, so one
 * dimension of its bounding box collapses, which is what separates it from the coal that is simply
 * part of the build. The winning cluster's block positions are locked for the rest of the fight and
 * only re-read after that, so the expensive part happens once and the per-tick cost is a handful of
 * block lookups.
 *
 * <p><b>The spawn delay is measured.</b> The countdown from a full ring starts at the configured
 * estimate, but the real gap to the bear appearing is recorded every fight and shown instead once it
 * is known - the same rule the rest of this mod's timers follow: state what was measured, mark what
 * is guessed.
 *
 * <p><b>The bow is tracked here because it is the bear's drop.</b> A dead Spirit Bear leaves the
 * Spirit Bow lying on the arena floor, and until somebody picks it up Thorn cannot be finished - so
 * the item on the ground is the single most time-critical thing in the fight, and it is small, dark
 * and easy to walk past. It is found in the same per-tick entity sweep as the bear rather than in a
 * second one, because both live in the same fight and the sweep is the expensive part.
 */
public final class SpiritBearTracker {

    private static final SpiritBearTracker INSTANCE = new SpiritBearTracker();

    /** Thorn's floor - the only place a Spirit Bear exists. */
    private static final int BEAR_FLOOR = 4;

    /** Ticks between scans: the ring changes once per spirit kill, nothing needs more than this. */
    private static final int SCAN_INTERVAL_TICKS = 10;

    /**
     * Horizontal reach of the arena sweep, in blocks. Generous on purpose: the ring is on the
     * audience wall and the fight is fought in the middle, so a reach that only just covers the
     * arena would find it on some spawns and not others. The sweep is coarse and happens at most
     * once per fight, so paying for the reach is the cheaper mistake. If the ring is never locked
     * (no {@code [SBS][Bear] Lantern ring locked} line), this is the number to raise.
     */
    private static final int SEARCH_RADIUS = 48;

    /** Vertical sweep window around the player - the ring hangs at and above arena floor level. */
    private static final int SEARCH_BELOW = 10;
    private static final int SEARCH_ABOVE = 16;

    /** Coarse sweep step: a ring of dozens of blocks is hit several times over by this lattice. */
    private static final int SEED_STRIDE = 2;

    /** Plausible ring sizes - below is scenery, above is a wall the fill leaked into. */
    private static final int MIN_RING_SLOTS = 12;
    private static final int MAX_RING_SLOTS = 96;

    /** A ring is flat: its thinnest bounding-box dimension is at most this many blocks. */
    private static final int MAX_RING_THICKNESS = 2;

    /** Cap on one flood fill, so a leak into a coal wall costs a bounded amount of work. */
    private static final int MAX_FILL = 400;

    /** Failed sweeps before giving up for this fight - the ring is either in range or it is not. */
    private static final int MAX_SEARCH_ATTEMPTS = 40;

    private static final int COLOR_RING_FULL = 0xFFFFC020;
    private static final int COLOR_BEAR = 0xFFFFC020;
    private static final int COLOR_BOW = 0xFFE87CFF;

    private static final String BEAR_NAME = "spirit bear";
    private static final String BOW_NAME = "spirit bow";

    /** The bow's SkyBlock id, which the dropped stack carries even when its name is recoloured. */
    private static final String BOW_ID = "SPIRIT_BOW";

    /** The locked ring: every slot position, in world coordinates. Empty until one is found. */
    private volatile List<BlockPos> ring = List.of();

    private volatile int lit;
    private volatile LivingEntity bear;
    private volatile ItemEntity bow;

    /** When the ring last completed, and when a bear was first seen after that. */
    private long ringFullAt;
    private long bearSeenAt;

    /** The measured full-ring→bear gap, in ms; 0 until one has been timed. */
    private long measuredDelayMs;

    private boolean fullAnnounced;
    private boolean bearAnnounced;
    private boolean bowAnnounced;

    private int tickCounter;
    private int searchAttempts;
    private boolean wasInArena;

    private SpiritBearTracker() {
    }

    public static SpiritBearTracker getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.SpiritBearSettings cfg() {
        return ConfigManager.getInstance().get().spiritBear;
    }

    // ---- read by the renderers / HUD ----------------------------------------------------------

    /** Whether we are in the F4/M4 boss room with the feature on - the only place anything shows. */
    public boolean inArena() {
        DungeonStateManager state = DungeonStateManager.getInstance();
        return cfg().enabled && state.inDungeon() && state.floorNumber() == BEAR_FLOOR
                && (state.floorType() == 'F' || state.floorType() == 'M')
                && state.phase() == DungeonEvents.Phase.BOSS;
    }

    /** Lit slots in the ring. */
    public int lit() {
        return lit;
    }

    /** Total slots in the ring, or 0 while none has been found. */
    public int ringSlots() {
        return ring.size();
    }

    /** Whether every slot is lit - the bear is on its way. */
    public boolean ringFull() {
        return ringFullAt != 0;
    }

    /** The Spirit Bear while one is alive and in range, else {@code null}. */
    public LivingEntity bear() {
        return bear;
    }

    /** The dropped Spirit Bow while it is still lying on the floor, else {@code null}. */
    public ItemEntity bow() {
        return bow;
    }

    /**
     * Seconds until the bear lands, counted from the full ring; -1 when the ring is not full yet or
     * the bear has already arrived. Uses the measured delay once one has been timed, the configured
     * estimate before that.
     */
    public int spawnEtaSeconds() {
        if (ringFullAt == 0 || bearSeenAt != 0) {
            return -1;
        }
        long delay = measuredDelayMs > 0 ? measuredDelayMs : cfg().spawnDelaySeconds * 1000L;
        long left = delay - (System.currentTimeMillis() - ringFullAt);
        return left <= 0 ? 0 : (int) Math.ceil(left / 1000.0);
    }

    /** The measured full-ring→bear gap in ms, or 0 while nothing has been timed. */
    public long measuredDelayMs() {
        return measuredDelayMs;
    }

    /** Whether the spawn countdown is a measured value rather than the configured estimate. */
    public boolean delayMeasured() {
        return measuredDelayMs > 0;
    }

    // ---- tick ---------------------------------------------------------------------------------

    /** Called every client tick; the ring scan itself is throttled to {@value #SCAN_INTERVAL_TICKS}. */
    public void onClientTick() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        if (!inArena() || level == null || player == null) {
            if (wasInArena) {
                wasInArena = false;
                reset();
            }
            return;
        }
        if (!wasInArena) {
            // Fresh arena: a previous fight's ring is in another world position and its timings
            // belong to that bear, so nothing carries over.
            wasInArena = true;
            reset();
        }
        scanEntities(level, player);
        if (++tickCounter < SCAN_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;
        if (ring.isEmpty()) {
            searchRing(level, player);
            return;
        }
        readRing(level, player);
    }

    private void reset() {
        ring = List.of();
        lit = 0;
        bear = null;
        bow = null;
        ringFullAt = 0;
        bearSeenAt = 0;
        measuredDelayMs = 0;
        fullAnnounced = false;
        bearAnnounced = false;
        bowAnnounced = false;
        tickCounter = 0;
        searchAttempts = 0;
    }

    // ---- the ring -----------------------------------------------------------------------------

    /**
     * One sweep for the ring: coarse seeds, then a flood fill from each, keeping the largest cluster
     * that is ring-sized and flat. Stops trying after {@value #MAX_SEARCH_ATTEMPTS} sweeps - if the
     * ring has not been in range by then it is not going to be, and re-sweeping forever would cost
     * for the whole fight.
     */
    private void searchRing(ClientLevel level, LocalPlayer player) {
        if (searchAttempts > MAX_SEARCH_ATTEMPTS) {
            return;
        }
        if (searchAttempts++ == MAX_SEARCH_ATTEMPTS) {
            // Said once, so the failure is diagnosable without the log filling up: either the ring
            // is out of SEARCH_RADIUS, or the fill merged it into the build and the shape check
            // rejected the result.
            SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][Bear] No lantern ring found within {} blocks - giving up for this fight",
                    SEARCH_RADIUS);
            return;
        }
        BlockPos origin = player.blockPosition();
        Set<Long> visited = new HashSet<>();
        List<BlockPos> best = null;
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx += SEED_STRIDE) {
            for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz += SEED_STRIDE) {
                int x = origin.getX() + dx;
                int z = origin.getZ() + dz;
                if (!level.hasChunkAt(x, z)) {
                    continue;
                }
                for (int dy = -SEARCH_BELOW; dy <= SEARCH_ABOVE; dy += SEED_STRIDE) {
                    BlockPos seed = new BlockPos(x, origin.getY() + dy, z);
                    if (!isRingBlock(level, seed) || visited.contains(seed.asLong())) {
                        continue;
                    }
                    List<BlockPos> cluster = fill(level, seed, visited);
                    if (isRing(cluster) && (best == null || cluster.size() > best.size())) {
                        best = cluster;
                    }
                }
            }
        }
        if (best == null) {
            return;
        }
        ring = List.copyOf(best);
        searchAttempts = 0;
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Bear] Lantern ring locked: {} slots around {},{},{}",
                ring.size(), ring.get(0).getX(), ring.get(0).getY(), ring.get(0).getZ());
        readRing(level, player);
    }

    /**
     * Flood fill over lantern/coal blocks from {@code seed}, using the full 3x3x3 neighbourhood: a
     * ring is drawn on a wall and its slots touch diagonally as often as face-to-face, so a
     * face-only fill would break it into arcs.
     */
    private List<BlockPos> fill(ClientLevel level, BlockPos seed, Set<Long> visited) {
        List<BlockPos> cluster = new ArrayList<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(seed);
        visited.add(seed.asLong());
        while (!queue.isEmpty() && cluster.size() < MAX_FILL) {
            BlockPos pos = queue.removeFirst();
            cluster.add(pos);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        BlockPos next = pos.offset(dx, dy, dz);
                        if (visited.contains(next.asLong()) || !isRingBlock(level, next)) {
                            continue;
                        }
                        visited.add(next.asLong());
                        queue.add(next);
                    }
                }
            }
        }
        return cluster;
    }

    /** A ring: the right number of slots, and flat enough to be a wall decoration. */
    private static boolean isRing(List<BlockPos> cluster) {
        if (cluster.size() < MIN_RING_SLOTS || cluster.size() > MAX_RING_SLOTS) {
            return false;
        }
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos pos : cluster) {
            minX = Math.min(minX, pos.getX());
            maxX = Math.max(maxX, pos.getX());
            minY = Math.min(minY, pos.getY());
            maxY = Math.max(maxY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxZ = Math.max(maxZ, pos.getZ());
        }
        int thinnest = Math.min(maxX - minX, Math.min(maxY - minY, maxZ - minZ)) + 1;
        return thinnest <= MAX_RING_THICKNESS;
    }

    private static boolean isRingBlock(ClientLevel level, BlockPos pos) {
        if (!level.hasChunkAt(pos.getX(), pos.getZ())) {
            return false;
        }
        Block block = level.getBlockState(pos).getBlock();
        return block == Blocks.SEA_LANTERN || block == Blocks.COAL_BLOCK;
    }

    /**
     * Re-reads the locked ring's slots. Going full arms the countdown; dropping back out of full
     * disarms it, because the ring empties again for the next bear and the fight can produce several.
     */
    private void readRing(ClientLevel level, LocalPlayer player) {
        int count = 0;
        for (BlockPos pos : ring) {
            if (level.hasChunkAt(pos.getX(), pos.getZ())
                    && level.getBlockState(pos).getBlock() == Blocks.SEA_LANTERN) {
                count++;
            }
        }
        lit = count;
        boolean full = count == ring.size() && !ring.isEmpty();
        if (full && ringFullAt == 0) {
            ringFullAt = System.currentTimeMillis();
            announceRingFull(player);
        } else if (!full && ringFullAt != 0) {
            // The ring reset for the next bear: forget this cycle so the next fill calls out again.
            ringFullAt = 0;
            bearSeenAt = 0;
            fullAnnounced = false;
            bearAnnounced = false;
            bowAnnounced = false;
        }
    }

    // ---- the bear and its bow -------------------------------------------------------------------

    /**
     * One sweep of the arena's entities for both things worth knowing about: the bear, found by its
     * nametag through the same stand→mob association the Mob Highlight uses, and the Spirit Bow it
     * drops, found as a loose item on the floor. Both are looked for in the same pass because the
     * iteration is what costs, not the two tests inside it.
     */
    private void scanEntities(ClientLevel level, LocalPlayer player) {
        LivingEntity foundBear = null;
        ItemEntity foundBow = null;
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof ItemEntity item) {
                if (foundBow == null && item.isAlive() && isSpiritBow(item.getItem())) {
                    foundBow = item;
                }
                continue;
            }
            if (foundBear != null || !(entity instanceof LivingEntity living) || living instanceof Player
                    || !living.hasCustomName()) {
                continue;
            }
            Component custom = living.getCustomName();
            String raw = custom == null ? "" : custom.getString();
            if (!raw.toLowerCase(Locale.ROOT).contains(BEAR_NAME)) {
                continue;
            }
            LivingEntity target = living instanceof ArmorStand stand
                    ? MobHighlightTracker.mobBelow(level, stand)
                    : living;
            if (target != null && target.isAlive()) {
                foundBear = target;
            }
        }
        bear = foundBear;
        bow = foundBow;
        if (foundBow != null) {
            announceBow(player);
        }
        if (foundBear == null) {
            return;
        }
        if (bearSeenAt == 0) {
            bearSeenAt = System.currentTimeMillis();
            if (ringFullAt != 0) {
                measuredDelayMs = Math.max(1, bearSeenAt - ringFullAt);
            }
        }
        announceBear(player);
    }

    /**
     * Whether a dropped stack is the Spirit Bow. The SkyBlock id is the answer when the stack carries
     * one; the display name is the fallback, because a dungeon drop lying on the floor is not
     * guaranteed to arrive with its attribute tag intact and a bow nobody notices is the whole
     * problem this is here to solve.
     */
    private static boolean isSpiritBow(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        String id = SkyblockItem.id(stack);
        if (id != null && id.equalsIgnoreCase(BOW_ID)) {
            return true;
        }
        return stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(BOW_NAME);
    }

    private void announceRingFull(LocalPlayer player) {
        if (fullAnnounced) {
            return;
        }
        fullAnnounced = true;
        if (!cfg().alert) {
            return;
        }
        DungeonAlert.getInstance().trigger("RING FULL", COLOR_RING_FULL, true, 1.2f);
        player.sendSystemMessage(Component.literal(
                "§8[§bSBS§8]§r §6Lantern ring full §7- Spirit Bear incoming"));
    }

    private void announceBear(LocalPlayer player) {
        if (bearAnnounced) {
            return;
        }
        bearAnnounced = true;
        if (!cfg().alert) {
            return;
        }
        DungeonAlert.getInstance().trigger("SPIRIT BEAR", COLOR_BEAR, true, 1.6f);
        player.sendSystemMessage(Component.literal("§8[§bSBS§8]§r §6Spirit Bear!"
                + (measuredDelayMs > 0 ? " §7after §f" + seconds(measuredDelayMs) : "")));
    }

    /**
     * Said once per bear cycle rather than once per sighting: the bow drops out of and back into
     * render range as the fight moves around it, and a call-out that fired on every reappearance
     * would be noise during exactly the seconds it matters. The ring resetting for the next bear is
     * what re-arms it.
     */
    private void announceBow(LocalPlayer player) {
        if (bowAnnounced) {
            return;
        }
        bowAnnounced = true;
        if (!cfg().alert) {
            return;
        }
        DungeonAlert.getInstance().trigger("SPIRIT BOW", COLOR_BOW, true, 1.6f);
        player.sendSystemMessage(Component.literal(
                "§8[§bSBS§8]§r §dSpirit Bow dropped §7- pick it up"));
    }

    /** {@code 3.2s} - the gap being timed here is a few seconds, so tenths are the useful unit. */
    public static String seconds(long ms) {
        return String.format(Locale.US, "%.1fs", Math.max(0, ms) / 1000.0);
    }
}
