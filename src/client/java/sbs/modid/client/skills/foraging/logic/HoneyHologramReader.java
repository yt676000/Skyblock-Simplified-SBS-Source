/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.foraging.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.util.StyledText;
import sbs.modid.client.skills.foraging.model.HoneyTimer;

import java.util.HashMap;
import java.util.Map;

/**
 * Looks for a remaining time Hypixel might publish above a honey tree, and says what it found.
 *
 * <p><b>Why this exists in this shape.</b> The request asks whether the game itself exposes the
 * remaining time - a hologram, item lore, the tab list, the scoreboard - and to sync to it rather
 * than trust our own clock if it does. That question cannot be answered from the repository: nobody
 * here has stood in front of a smeared honey tree. What <i>is</i> known is that Hypixel's universal
 * pattern for per-object state is an armour-stand nametag above the object, and this mod already
 * reads those in two other features.
 *
 * <p>So the class does two separable things, and only one of them can be wrong:
 *
 * <ul>
 *   <li><b>It reports.</b> Every nametag standing near a tracked tree is logged, once per tree and
 *       only when the text changes. This half runs whenever the module does, because it makes no
 *       claim - it is the capture that answers the question in one session, and it needs no new
 *       build to do it.</li>
 *   <li><b>It syncs, if asked.</b> When a nametag carries a {@code MM:SS} (or {@code 1m 30s})
 *       token and the player has switched the sync on, that tree's timer is re-based onto the read
 *       value and stops following the configured duration - the source of truth beating the
 *       assumption, which is what was asked for.</li>
 * </ul>
 *
 * <p><b>The sync ships off.</b> It is written against a hologram nobody has seen, so on by default
 * would mean preferring an unverified parse to an unverified constant - trading one guess for
 * another while looking like a measurement. The reporting half is what turns it on honestly.
 *
 * <p><b>It never invents a timer.</b> A nametag is only ever read for a tree that already has an
 * entry, so a stray countdown somewhere in the world cannot conjure one.
 */
public final class HoneyHologramReader {

    private static final HoneyHologramReader INSTANCE = new HoneyHologramReader();

    /** Scanning walks the entity list, so it runs on its own slow clock rather than per tick. */
    private static final long SCAN_INTERVAL_MS = 2_000L;

    /** Beyond this the entities are not even sent to the client, so scanning further is wasted. */
    private static final double SCAN_RANGE = 48.0;

    /** The same nametag is not logged again inside this window. */
    private static final long REPEAT_LOG_MS = 60_000L;

    /** A re-base smaller than this is noise, and would rewrite the profile file every scan. */
    private static final long RESYNC_THRESHOLD_MS = 2_000L;

    /** Tree key -> what its nametag last said and when that was logged. */
    private final Map<String, String> lastSeen = new HashMap<>();
    private final Map<String, Long> lastLoggedAt = new HashMap<>();

    private long lastScanAt;

    private HoneyHologramReader() {
    }

    public static HoneyHologramReader getInstance() {
        return INSTANCE;
    }

    /** Called from the honey tracker's throttled check. */
    public void tick(SBSConfig.HoneySettings cfg, long now) {
        if (now - lastScanAt < SCAN_INTERVAL_MS) {
            return;
        }
        lastScanAt = now;
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null) {
            return;
        }
        String island = SkyBlockLocation.island();
        if (island == null || island.isEmpty()) {
            return;
        }
        HoneyTimerStore store = HoneyTimerStore.getInstance();
        boolean changed = false;
        for (HoneyTimer timer : store.all()) {
            if (!timer.valid() || !island.equals(timer.island)) {
                continue;
            }
            if (minecraft.player.distanceToSqr(timer.x, timer.y, timer.z) > SCAN_RANGE * SCAN_RANGE) {
                continue;
            }
            String nametag = nametagNear(level, timer, Math.max(1, cfg.toleranceBlocks));
            if (nametag == null) {
                continue;
            }
            report(timer, nametag, now);
            if (cfg.syncFromWorld && applySync(store, timer, nametag, now)) {
                changed = true;
            }
        }
        if (changed) {
            store.touch();
        }
    }

    /** The custom name of the nearest armour stand standing over this tree, or {@code null}. */
    private static String nametagNear(ClientLevel level, HoneyTimer timer, int tolerance) {
        double limit = (double) tolerance * tolerance;
        String best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof ArmorStand stand) || !stand.hasCustomName()) {
                continue;
            }
            double dy = stand.getY() - timer.y;
            if (Math.abs(dy) > HoneyTreeCatalog.VERTICAL_TOLERANCE) {
                continue;
            }
            double dx = stand.getX() - timer.x;
            double dz = stand.getZ() - timer.z;
            double squared = dx * dx + dz * dz;
            if (squared > limit || squared >= bestDistance) {
                continue;
            }
            var custom = stand.getCustomName();
            String raw = custom == null ? null : custom.getString();
            if (raw == null || raw.isBlank()) {
                continue;
            }
            bestDistance = squared;
            best = StyledText.strip(raw).trim();
        }
        return best;
    }

    /**
     * Writes down what stands over the tree.
     *
     * <p>Once per tree and only on a change, because the interesting artefact is the <i>set</i> of
     * wordings a honey tree goes through, not one line repeated every two seconds.
     */
    private void report(HoneyTimer timer, String nametag, long now) {
        String previous = lastSeen.get(timer.key);
        Long loggedAt = lastLoggedAt.get(timer.key);
        boolean isNew = !nametag.equals(previous);
        if (!isNew && loggedAt != null && now - loggedAt < REPEAT_LOG_MS) {
            return;
        }
        lastSeen.put(timer.key, nametag);
        lastLoggedAt.put(timer.key, now);
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Honey] nametag over {} at {} {} {}: \"{}\"",
                HoneyTreeTimers.displayName(timer), timer.x, timer.y, timer.z, nametag);
    }

    /** Re-bases the timer onto a time read out of the world. Returns whether anything moved. */
    private static boolean applySync(HoneyTimerStore store, HoneyTimer timer, String nametag,
                                     long now) {
        long read = HoneyTimer.parseDuration(nametag);
        if (read <= 0L) {
            return false;
        }
        long predicted = timer.remainingMs(now);
        if (Math.abs(predicted - read) < RESYNC_THRESHOLD_MS) {
            return false;
        }
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Honey] syncing {} to the world: predicted {}, read {} from \"{}\"",
                timer.key, HoneyTimer.clock(predicted), HoneyTimer.clock(read), nametag);
        timer.startedAt = now;
        timer.durationMs = read;
        // A measured value is evidence, so it stops following the configured assumption.
        timer.followsConfig = false;
        timer.notifiedWarn = false;
        timer.notifiedDone = false;
        return true;
    }

    /** Drops what was seen on the instance just left. */
    public void onWorldChange() {
        lastSeen.clear();
        lastLoggedAt.clear();
        lastScanAt = 0L;
    }
}
