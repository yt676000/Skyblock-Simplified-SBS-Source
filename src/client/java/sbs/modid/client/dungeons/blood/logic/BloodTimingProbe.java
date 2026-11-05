/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.blood.logic;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.dungeons.events.ChatPatternRegistry;
import sbs.modid.client.dungeons.run.logic.DungeonStateManager;
import sbs.modid.client.ui.hud.logic.ServerStatsTracker;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Logs the Watcher's timing, one line per event, so ordinary blood camps produce the numbers
 * {@link BloodMoveTimer} has to be re-derived from after Hypixel's 2026-10 patch.
 *
 * <p>Runs in every dungeon while {@link BloodMoveTimer#TIMING_VERIFIED} is false, and only writes to
 * the log - nothing is drawn, said or sent. Per blood room it records, on the same server-tick clock
 * the timer counts on ({@link BloodCampClock#serverSeconds}), every Watcher line, every summon as it
 * spawns and as it becomes hittable ({@link BloodRoomTracker}), every moment the room is empty
 * again, and the closing line. Each line also carries the wall time and the measured tick rate, so
 * the tick conversion itself can be checked against the log:
 *
 * <pre>
 * [SBS][BloodTiming] --- blood room on F7, opened by watcher line ---
 * [SBS][BloodTiming] t=0.00 watcher: 'Things feel a little more roomy now, eh?' (wall 0.00s, tps 19.6)
 * [SBS][BloodTiming] t=3.41 summon spawned #1 Revoker (wall 3.48s, tps 19.6)
 * [SBS][BloodTiming] t=5.37 summon hittable #1 Revoker (wall 5.47s, tps 19.6)
 * [SBS][BloodTiming] t=21.80 watcher: 'Let's see how you can handle this.' [cue] (wall 22.30s, tps 19.4)
 * </pre>
 *
 * <p>The clock opens at the first event of a room and closes on the Watcher's closing line, a world
 * change, or {@link #QUIET_CLOSE_SECONDS} without any event.
 */
public final class BloodTimingProbe {

    static final String TAG = "[SBS][BloodTiming] ";

    /** The Watcher speaking, with what he said in group 1. */
    private static final Pattern WATCHER_LINE = Pattern.compile(BloodMoveTimer.WATCHER + "\\s*(.*)");
    private static final Pattern CUE = Pattern.compile("(?i)" + BloodMoveTimer.CUE);
    private static final Pattern DONE = Pattern.compile("(?i)" + BloodMoveTimer.DONE);

    /** A room with no event for this long ended without its closing line. */
    static final double QUIET_CLOSE_SECONDS = 120.0;

    private final Consumer<String> sink;

    private boolean open;
    private long lastStep;
    /** Server seconds since the room opened - the timer's clock. */
    private double t;
    /** Wall seconds since the room opened, to check the tick conversion against. */
    private double wall;
    private double lastEventAt;
    private boolean heardWatcher;
    private int summons;
    /** Spawn number per entity id, so "hittable #n" names the same mob as "spawned #n". */
    private final Map<Integer, Integer> numbers = new HashMap<>();
    private final Map<Integer, String> names = new HashMap<>();

    BloodTimingProbe(Consumer<String> sink) {
        this.sink = sink;
    }

    private static final class Holder {
        static final BloodTimingProbe INSTANCE = create();
    }

    public static BloodTimingProbe getInstance() {
        return Holder.INSTANCE;
    }

    private static BloodTimingProbe create() {
        BloodTimingProbe probe = new BloodTimingProbe(SkyblockSimplifiedSBS.LOGGER::info);
        // Its own pattern rather than a hook in the timer's three: the timer splits his lines by
        // meaning and drops the text, and the text is exactly what this has to record.
        ChatPatternRegistry.getInstance().register(WATCHER_LINE.pattern(), matcher -> {
            if (active()) {
                probe.watcherLine(matcher.group(1).trim(), System.currentTimeMillis(), tps(), floor());
            }
        }, "blood timing: watcher line");
        return probe;
    }

    /** Only while the timing is unverified, and only in a dungeon. */
    private static boolean active() {
        return !BloodMoveTimer.TIMING_VERIFIED && DungeonStateManager.getInstance().inDungeon();
    }

    private static double tps() {
        return ServerStatsTracker.getInstance().tps();
    }

    private static String floor() {
        return DungeonStateManager.getInstance().floorLabel();
    }

    // ---- called by the game side ---------------------------------------------------------------

    /** A summon body was first seen in the room; {@code name} may still be null. */
    public void onSummonSpawned(int entityId, String name) {
        if (active()) {
            summonSpawned(entityId, name, System.currentTimeMillis(), tps(), floor());
        }
    }

    /** A summon landed / became hittable. */
    public void onSummonHittable(int entityId, String name) {
        if (active()) {
            summonHittable(entityId, name, System.currentTimeMillis(), tps(), floor());
        }
    }

    /** The room went from having summons to having none: the last one is dead. */
    public void onRoomClear() {
        if (active()) {
            roomClear(System.currentTimeMillis(), tps());
        }
    }

    /** Per Catacombs tick: keeps the clock stepping at the current tick rate. */
    public void onClientTick() {
        if (open) {
            tick(System.currentTimeMillis(), tps());
        }
    }

    public void onWorldChange() {
        close("world change");
    }

    // ---- the probe itself, on explicit time ----------------------------------------------------

    void watcherLine(String line, long now, double tps, String floor) {
        openIfNeeded(now, tps, floor, "watcher line");
        boolean opening = !heardWatcher;
        heardWatcher = true;
        boolean done = DONE.matcher(line).find();
        String kind = done ? " [done]" : CUE.matcher(line).find() ? " [cue]" : opening ? " [opening]" : "";
        event(tps, "watcher: '" + line + "'" + kind);
        if (done) {
            close("closing line");
        }
    }

    void summonSpawned(int entityId, String name, long now, double tps, String floor) {
        openIfNeeded(now, tps, floor, "summon");
        int n = ++summons;
        numbers.put(entityId, n);
        if (name != null && !name.isEmpty()) {
            names.put(entityId, name);
        }
        event(tps, "summon spawned #" + n + " " + label(entityId));
    }

    void summonHittable(int entityId, String name, long now, double tps, String floor) {
        openIfNeeded(now, tps, floor, "summon");
        Integer n = numbers.get(entityId);
        if (n == null) {
            n = ++summons; // spawned before the player was in the room to see it
            numbers.put(entityId, n);
        }
        if (name != null && !name.isEmpty()) {
            names.put(entityId, name);
        }
        event(tps, "summon hittable #" + n + " " + label(entityId));
    }

    void roomClear(long now, double tps) {
        if (!open) {
            return;
        }
        step(now, tps);
        event(tps, "room clear (last summon dead)");
    }

    void tick(long now, double tps) {
        if (!open) {
            return;
        }
        step(now, tps);
        if (t - lastEventAt > QUIET_CLOSE_SECONDS) {
            close("quiet for " + (int) QUIET_CLOSE_SECONDS + "s");
        }
    }

    boolean isOpen() {
        return open;
    }

    private void openIfNeeded(long now, double tps, String floor, String by) {
        if (open) {
            step(now, tps);
            return;
        }
        open = true;
        lastStep = now;
        t = 0;
        wall = 0;
        lastEventAt = 0;
        heardWatcher = false;
        summons = 0;
        numbers.clear();
        names.clear();
        sink.accept(TAG + "--- blood room on " + floor + ", opened by " + by + " ---");
    }

    private void close(String why) {
        if (!open) {
            return;
        }
        open = false;
        numbers.clear();
        names.clear();
        sink.accept(TAG + String.format(Locale.ROOT, "--- room closed at t=%.2f: %s ---", t, why));
    }

    private void step(long now, double tps) {
        long stepMs = now - lastStep;
        lastStep = now;
        if (stepMs > 0) {
            t += BloodCampClock.serverSeconds(stepMs, tps);
            wall += stepMs / 1000.0;
        }
    }

    private void event(double tps, String what) {
        lastEventAt = t;
        sink.accept(format(t, wall, tps, what));
    }

    private String label(int entityId) {
        String name = names.get(entityId);
        return name == null ? "(no tag yet)" : name;
    }

    /** One log line: {@code [SBS][BloodTiming] t=<server s> <event> (wall <s>s, tps <rate>)}. */
    static String format(double serverSeconds, double wallSeconds, double tps, String event) {
        return TAG + String.format(Locale.ROOT, "t=%.2f %s (wall %.2fs, tps %.1f)",
                serverSeconds, event, wallSeconds, tps);
    }
}
