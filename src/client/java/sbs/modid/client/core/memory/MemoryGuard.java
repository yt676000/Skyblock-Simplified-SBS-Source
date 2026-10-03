/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.memory;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;

import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;

/**
 * Watches the heap over a long session, says when it is being retained rather than merely used, and
 * gives memory back before the game dies rather than after.
 *
 * <p><b>The problem this is actually about.</b> A SkyBlock session is dozens of server transfers, and
 * every one of them throws away a whole {@link ClientLevel} and builds a new one. A level is one of
 * the largest objects a client ever holds - its chunk cache alone is hundreds of megabytes - so if
 * anything at all keeps a reference to the old one, that memory is never coming back. Do it thirty
 * times in an evening and the game dies. Nothing about it looks like a leak while it happens: the
 * framerate is fine, memory simply never returns to where it started.
 *
 * <p><b>Two things that are commonly done here and are not worth doing.</b> Calling {@code System.gc()}
 * on a timer does not fix a leak - a leak is memory that is still <i>reachable</i>, which is exactly
 * the memory a collector must keep - and a full collection is a visible stutter, so a periodic one
 * trades frames for nothing. And measuring "memory used" tells you almost nothing, because between
 * collections it climbs on every healthy client in existence.
 *
 * <p><b>What is measured instead: the floor.</b> The interesting number is the <i>lowest</i> the heap
 * gets, because that is what survived the last collection. A client that is merely busy has a flat
 * floor and a sawtooth above it; a client that is retaining has a floor that walks upward. Sampling
 * the minimum over rolling windows and comparing them is a cheap and honest leak signal, and it is
 * the one this reports.
 *
 * <p><b>What is done about it.</b> Retention is found, not fixed - so the loud part of this class is
 * a diagnostic: a level that is still alive long after it was replaced is named in the log, along
 * with how many collections it survived. What <i>is</i> fixed is our own share: under real pressure
 * this mod hands back the memory it is holding, starting with the far-terrain chunks, which are
 * usually the largest single thing it owns and are the one thing it can rebuild for free. A last
 * resort collection is kept for the case where the alternative is the game ending, and rate-limited
 * so it can never become the periodic stutter described above.
 */
public final class MemoryGuard {

    /** How often the heap is sampled. Often enough to catch the floor, rare enough to be free. */
    private static final int SAMPLE_TICKS = 100;   // 5 s

    /** Samples per window, and how many windows of history are kept - 1 min each, 10 min total. */
    private static final int SAMPLES_PER_WINDOW = 12;
    private static final int WINDOWS = 10;

    /** A floor that climbs by more than this between the oldest and newest window is reported. */
    private static final long FLOOR_RISE_REPORT = 256L * 1024L * 1024L;

    /** How long a replaced level is given to disappear before it is called retained. */
    private static final long RETENTION_GRACE_MS = 60_000L;

    /** A retained-level verdict is only trusted once this many collections have run since. */
    private static final int RETENTION_MIN_COLLECTIONS = 2;

    /** Nothing is done twice inside this window, so relief can never become a stutter loop. */
    private static final long RELIEF_COOLDOWN_MS = 60_000L;

    /** The last-resort collection is far more expensive than relief, so it waits far longer. */
    private static final long COLLECT_COOLDOWN_MS = 300_000L;

    private static int tickCounter;
    private static int nextSample;

    private static final long[] FLOORS = new long[WINDOWS];
    private static int floorCount;
    private static int floorIndex;
    private static long windowFloor = Long.MAX_VALUE;
    private static int samplesInWindow;

    /**
     * Weak on purpose, both of them. A guard that held the level it is watching would be the leak it
     * is looking for.
     */
    private static WeakReference<ClientLevel> currentLevel;
    private static WeakReference<ClientLevel> retiredLevel;
    private static long retiredAt;
    private static long retiredCollections;

    private static long lastRelief;
    private static long lastCollect;

    /** Last sampled heap use, for the settings status line. */
    private static long lastUsed;
    private static long lastMax = 1;

    private MemoryGuard() {
    }

    private static SBSConfig.MemorySettings cfg() {
        return ConfigManager.getInstance().get().memory;
    }

    /** Driven once per client tick; samples every {@value #SAMPLE_TICKS} ticks. */
    /** The most recent window's heap floor in bytes, or -1 before the first window closed. For the perf KPIs. */
    public static long latestFloorBytes() {
        return floorCount == 0 ? -1 : FLOORS[(floorIndex - 1 + WINDOWS) % WINDOWS];
    }

    public static void tick(Minecraft minecraft) {
        if (!cfg().enabled) {
            return;
        }
        tickCounter++;
        if (tickCounter < nextSample) {
            return;
        }
        nextSample = tickCounter + SAMPLE_TICKS;

        Runtime runtime = Runtime.getRuntime();
        long max = runtime.maxMemory();
        long used = runtime.totalMemory() - runtime.freeMemory();
        lastUsed = used;
        lastMax = Math.max(1, max);

        trackFloor(used);
        trackLevel(minecraft);
        applyPressure(minecraft, used, lastMax);
    }

    /**
     * Records the lowest heap seen per window and reports a floor that walks upward - the signal
     * that memory is being kept rather than merely used.
     */
    private static void trackFloor(long used) {
        windowFloor = Math.min(windowFloor, used);
        if (++samplesInWindow < SAMPLES_PER_WINDOW) {
            return;
        }
        FLOORS[floorIndex] = windowFloor;
        floorIndex = (floorIndex + 1) % WINDOWS;
        floorCount = Math.min(WINDOWS, floorCount + 1);
        windowFloor = Long.MAX_VALUE;
        samplesInWindow = 0;
        if (floorCount < WINDOWS) {
            return;
        }
        long oldest = FLOORS[floorIndex];                      // the slot about to be overwritten
        long newest = FLOORS[(floorIndex + WINDOWS - 1) % WINDOWS];
        if (newest - oldest > FLOOR_RISE_REPORT) {
            SkyblockSimplifiedSBS.LOGGER.warn(
                    "[SBS][Memory] heap floor rose from {} MB to {} MB over the last {} minutes - "
                            + "something is being retained, not just used",
                    oldest / (1024 * 1024), newest / (1024 * 1024), WINDOWS);
        }
    }

    /**
     * Notices a level being replaced and, a minute later, whether the old one is still alive.
     *
     * <p>The collection count is what makes the answer trustworthy: a weak reference is only cleared
     * by a collector that has actually run, so an object that is unreachable but not yet collected
     * would otherwise be reported as a leak on a client with plenty of headroom.
     */
    private static void trackLevel(Minecraft minecraft) {
        ClientLevel level = minecraft.level;
        ClientLevel seen = currentLevel == null ? null : currentLevel.get();
        if (seen != level) {
            if (seen != null) {
                retiredLevel = currentLevel;
                retiredAt = System.currentTimeMillis();
                retiredCollections = collections();
            }
            currentLevel = level == null ? null : new WeakReference<>(level);
        }
        if (retiredLevel == null || System.currentTimeMillis() - retiredAt < RETENTION_GRACE_MS) {
            return;
        }
        long ran = collections() - retiredCollections;
        if (ran < RETENTION_MIN_COLLECTIONS) {
            return;   // no collector has looked at it yet - the question is not answerable
        }
        if (retiredLevel.get() != null) {
            SkyblockSimplifiedSBS.LOGGER.warn(
                    "[SBS][Memory] the level left {} s ago is STILL in memory after {} collections - "
                            + "something is holding a reference to it (its chunk cache goes with it). "
                            + "Heap now {} MB of {} MB",
                    (System.currentTimeMillis() - retiredAt) / 1000, ran,
                    lastUsed / (1024 * 1024), lastMax / (1024 * 1024));
        }
        retiredLevel = null;
    }

    private static long collections() {
        long total = 0;
        for (var bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = bean.getCollectionCount();
            if (count > 0) {
                total += count;
            }
        }
        return total;
    }

    /**
     * Hands memory back when the heap is genuinely tight. Relief first - our own caches, which cost
     * nothing to rebuild - and only past the emergency mark the expensive measures.
     */
    private static void applyPressure(Minecraft minecraft, long used, long max) {
        SBSConfig.MemorySettings settings = cfg();
        int percent = (int) (used * 100L / max);
        if (percent < Math.max(50, Math.min(99, settings.relievePercent))) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean emergency = percent >= Math.max(60, Math.min(100, settings.emergencyPercent));
        if (now - lastRelief < RELIEF_COOLDOWN_MS && !emergency) {
            return;
        }
        lastRelief = now;

        minecraft.particleEngine.clearParticles();
        sbs.modid.client.helper.terrain.FarTerrainManager.getInstance().releaseUnderPressure(emergency);
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Memory] heap at {}% ({} MB of {} MB) - released cached data{}",
                percent, used / (1024 * 1024), max / (1024 * 1024),
                emergency ? " and unloaded remembered terrain" : "");

        // Only here, and only rarely. A collection cannot free anything that is still referenced, so
        // this is not a fix for retention - it is the difference between a stutter and an OOM when
        // the heap is genuinely full of garbage that nothing has got round to collecting yet.
        if (emergency && now - lastCollect > COLLECT_COOLDOWN_MS) {
            lastCollect = now;
            System.gc();
        }
    }

    /** The settings status line: where the heap is, and where its floor has been. */
    public static String statusLine() {
        if (!cfg().enabled) {
            return "Off - no heap watching, no relief under pressure";
        }
        int percent = (int) (lastUsed * 100L / Math.max(1, lastMax));
        String line = lastUsed / (1024 * 1024) + " MB of " + lastMax / (1024 * 1024)
                + " MB (" + percent + "%)";
        if (floorCount >= 2) {
            long newest = FLOORS[(floorIndex + WINDOWS - 1) % WINDOWS];
            line += ", floor " + newest / (1024 * 1024) + " MB";
        }
        return line;
    }
}
