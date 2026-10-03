/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.vampire.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.alert.Alerts;
import sbs.modid.client.core.audio.SbsAudio;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.helper.rift.logic.RiftState;
import sbs.modid.client.helper.rift.model.Certainty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The six Blood Effigies around Stillgore Château: which are standing, which are broken, and how
 * long until the broken ones come back.
 *
 * <p><b>Found by looking, not by a coordinate list.</b> An effigy is a stack of Redstone Blocks on a
 * Bedrock base, which is a shape nothing else in the château has, so the scanner finds them from the
 * world itself. That is deliberately not a data file: coordinates would be six numbers to get wrong,
 * they would need re-checking every time Hypixel moves a wall, and the block state is the thing this
 * feature actually cares about anyway - a coordinate cannot tell you how much of an effigy is left.
 *
 * <p><b>Two mechanics are modelled, and the second is the one worth having.</b>
 * <ul>
 *   <li>An effigy breaks <b>top to bottom</b> - three redstone blocks, and the count remaining is
 *       read straight off the world, so progress is exact rather than inferred.</li>
 *   <li><b>Hitting the base afterwards resets that effigy's respawn timer.</b> This is the case the
 *       helper exists for: it is invisible, it is easy to do by accident while swinging at something
 *       else, and the only feedback is an effigy that quietly never comes back. So the warning fires
 *       when the player is <i>aiming at</i> the base of a broken one, before the swing, rather than
 *       reporting it afterwards.</li>
 * </ul>
 *
 * <p><b>Certainty.</b> The twenty-minute respawn is a wiki figure and has not been watched here, so
 * the countdown is drawn as unconfirmed and says so. The block-count progress and the base-hit
 * warning are read from the world and are exact regardless.
 *
 * <p><b>This helper never acts.</b> It reads block states and the crosshair target; it does not
 * write to input, rotation or item use, and there is no code path here that can.
 */
public final class BloodEffigyTracker {

    private static final BloodEffigyTracker INSTANCE = new BloodEffigyTracker();

    /** How many Blood Effigies exist around the château. */
    public static final int EFFIGY_COUNT = 6;

    /** Redstone blocks on a full effigy. */
    private static final int FULL_BLOCKS = 3;

    /**
     * How long a broken effigy takes to come back. Wiki-derived and unconfirmed - the buff it grants
     * lasts twenty minutes and the effigy is said to return with it.
     */
    private static final long RESPAWN_MS = 20 * 60_000L;
    private static final Certainty RESPAWN_CERTAINTY = Certainty.WIKI;

    private static final long SCAN_MS = 500L;

    /** Horizontal reach of the sweep. The château is large; this covers the effigies near you. */
    private static final int RADIUS = 48;

    /** Vertical band around the player to sweep. Effigies sit on the ground, not on towers. */
    private static final int Y_BAND = 24;

    /** Cap on scanned bases, so a pathological area can never blow the scan up. */
    private static final int MAX_BASES = 64;

    /** How close the crosshair target must be to a broken base to warn about it. */
    private static final double BASE_WARN_RANGE = 6.0;

    /** The zone name the château goes by; matched loosely because of the circumflex. */
    private static final String CHATEAU = "stillgore";

    /** One effigy: where its base is, how much is left, and when it was last seen broken. */
    public record Effigy(BlockPos base, int blocksLeft, long brokenAt) {

        public boolean broken() {
            return blocksLeft <= 0;
        }

        /** Milliseconds until it should be back, or {@code -1} when it is standing or unknown. */
        public long respawnIn() {
            if (!broken() || brokenAt <= 0L) {
                return -1;
            }
            return Math.max(0, brokenAt + RESPAWN_MS - System.currentTimeMillis());
        }
    }

    /** Live effigies, keyed on the packed base position so identity survives a rescan. */
    private final Map<Long, Effigy> effigies = new LinkedHashMap<>();

    private volatile List<Effigy> snapshot = List.of();
    private long lastScanAt;

    /** The base the aim warning last fired for, so it speaks once per look rather than per tick. */
    private long warnedBase = Long.MIN_VALUE;
    private long warnedAt;

    /** A repeat warning for the same base is allowed again after this long. */
    private static final long WARN_REPEAT_MS = 15_000L;

    private BloodEffigyTracker() {
        RiftState.getInstance().register(new RiftState.Listener() {
            @Override
            public void onRiftExit() {
                reset();
            }
        });
    }

    public static BloodEffigyTracker getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.VampireSettings cfg() {
        return ConfigManager.getInstance().get().vampire;
    }

    /** The effigies to draw this frame; empty when the feature is idle. */
    public List<Effigy> effigies() {
        return snapshot;
    }

    /** How many are standing right now, of {@link #EFFIGY_COUNT}. */
    public int standingCount() {
        int standing = 0;
        for (Effigy effigy : snapshot) {
            if (!effigy.broken()) {
                standing++;
            }
        }
        return standing;
    }

    /** Whether the respawn countdown is a confirmed figure or a wiki one. */
    public static Certainty respawnCertainty() {
        return RESPAWN_CERTAINTY;
    }

    // ------------------------------------------------------------------ tick

    /** Called once per client tick; the scan itself is throttled. */
    public void tick(Minecraft minecraft) {
        SBSConfig.VampireSettings cfg = cfg();
        if (!cfg.enabled || !cfg.effigies || !inChateau()) {
            reset();
            return;
        }
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null) {
            reset();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastScanAt >= SCAN_MS) {
            lastScanAt = now;
            scan(level, player.blockPosition(), now);
        }
        if (cfg.effigyResetWarning) {
            checkAim(minecraft, now);
        }
    }

    private static boolean inChateau() {
        if (!RiftState.getInstance().inRift()) {
            return false;
        }
        String zone = SkyBlockLocation.zone().toLowerCase(Locale.ROOT);
        return zone.contains(CHATEAU);
    }

    /**
     * Sweeps for Bedrock bases and counts the Redstone Blocks standing on each.
     *
     * <p>Anchored on the <b>bedrock</b> rather than on the redstone because that is the part that
     * does not move: an effigy mid-break has a different number of redstone blocks every few seconds,
     * and keying on those would make every hit look like a new effigy appearing and an old one
     * vanishing. The base is also exactly what the reset warning is about.
     */
    private void scan(ClientLevel level, BlockPos around, long now) {
        int px = around.getX();
        int py = around.getY();
        int pz = around.getZ();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int found = 0;

        for (int x = px - RADIUS; x <= px + RADIUS && found < MAX_BASES; x++) {
            for (int z = pz - RADIUS; z <= pz + RADIUS && found < MAX_BASES; z++) {
                for (int y = py - Y_BAND; y <= py + Y_BAND; y++) {
                    if (!level.getBlockState(cursor.set(x, y, z)).is(Blocks.BEDROCK)) {
                        continue;
                    }
                    // A bedrock block only counts as an effigy base if it either carries redstone
                    // now or has done so while this client was watching - the Rift has bedrock in
                    // its floor, and every one of those would otherwise become a "broken effigy"
                    // with a countdown attached.
                    int stacked = countRedstoneAbove(level, cursor, x, y, z);
                    long key = BlockPos.asLong(x, y, z);
                    if (stacked == 0 && !effigies.containsKey(key)) {
                        continue;
                    }
                    found++;
                    record(key, new BlockPos(x, y, z), stacked, now);
                }
            }
        }
        snapshot = List.copyOf(effigies.values());
    }

    private static int countRedstoneAbove(ClientLevel level, BlockPos.MutableBlockPos cursor,
                                          int x, int y, int z) {
        int stacked = 0;
        for (int i = 1; i <= FULL_BLOCKS; i++) {
            if (!level.getBlockState(cursor.set(x, y + i, z)).is(Blocks.REDSTONE_BLOCK)) {
                break;
            }
            stacked++;
        }
        return stacked;
    }

    /** Folds one scanned base into the live map, noting the moment it became broken. */
    private void record(long key, BlockPos base, int blocksLeft, long now) {
        Effigy previous = effigies.get(key);
        long brokenAt = previous == null ? 0L : previous.brokenAt();
        if (blocksLeft <= 0) {
            // Only stamp the break time on the transition. Re-stamping every scan would push the
            // countdown forward forever and it would never reach zero.
            if (previous == null || previous.blocksLeft() > 0) {
                brokenAt = now;
                if (previous != null) {
                    SkyblockSimplifiedSBS.LOGGER.info("[SBS][Effigy] broken at {}", base);
                }
            }
        } else {
            brokenAt = 0L;
        }
        effigies.put(key, new Effigy(base, blocksLeft, brokenAt));
    }

    /**
     * Warns while the crosshair is on the base of a broken effigy.
     *
     * <p>Before the swing, not after: the reset is silent and irreversible for another twenty
     * minutes, so a warning that arrives once the damage is done is only an explanation.
     */
    private void checkAim(Minecraft minecraft, long now) {
        HitResult hit = minecraft.hitResult;
        if (!(hit instanceof BlockHitResult block) || hit.getType() != HitResult.Type.BLOCK) {
            return;
        }
        BlockPos aimed = block.getBlockPos();
        Effigy effigy = effigies.get(aimed.asLong());
        if (effigy == null || !effigy.broken()) {
            return;
        }
        if (minecraft.player != null
                && minecraft.player.blockPosition().distSqr(aimed)
                > BASE_WARN_RANGE * BASE_WARN_RANGE) {
            return;
        }
        long key = aimed.asLong();
        if (key == warnedBase && now - warnedAt < WARN_REPEAT_MS) {
            return;
        }
        warnedBase = key;
        warnedAt = now;
        Alerts.send(new Alerts.Alert("Do not hit this base",
                "Hitting a broken effigy's base restarts its respawn timer",
                SbsAudio.Tone.ALARM, null), cfg().alertChannels);
    }

    private void reset() {
        if (!effigies.isEmpty()) {
            effigies.clear();
            snapshot = List.of();
        }
        warnedBase = Long.MIN_VALUE;
    }

    /** The effigies sorted for the card: broken and closest to returning first. */
    public List<Effigy> forCard() {
        List<Effigy> rows = new ArrayList<>(snapshot);
        rows.sort((a, b) -> {
            if (a.broken() != b.broken()) {
                return a.broken() ? -1 : 1;
            }
            return Long.compare(a.respawnIn(), b.respawnIn());
        });
        return rows;
    }
}
