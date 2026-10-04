/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.metaldetector.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.pathfinding.Waypoint;
import sbs.modid.client.core.pathfinding.WaypointStore;
import sbs.modid.client.helper.timers.ServerWorldTime;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The game-facing half of the Metal Detector helper: readings in, a waypoint out.
 *
 * <p>{@link MetalDetectorSolver} does the maths and {@link MetalDetectorHunt} decides which readings
 * belong together; neither knows about Minecraft. This class supplies the position and the distance
 * and decides when to throw everything away. The split is there because only the pure halves can be
 * tested - a reading needs a player in a world.
 *
 * <h2>Keeping the sample set honest</h2>
 * Every sample is a promise that the player was <i>there</i> when the detector said <i>that</i>, and
 * three things break the promise:
 * <ul>
 *   <li><b>Moving.</b> The action bar updates on its own schedule, so a reading taken mid-sprint
 *       pairs a fresh position with a stale distance. {@link MetalDetectorHunt#offer} only keeps
 *       samples from a standstill, or at least {@link MetalDetectorSolver#MIN_SAMPLE_SPACING} blocks
 *       from the last one.</li>
 *   <li><b>A find.</b> {@code You found ... with your Metal Detector!} (CONFIRMED) means that chest
 *       is gone and the readings now describe the next one. {@link #onFound} ends the hunt.</li>
 *   <li><b>A new lobby, or leaving the zone.</b> Same problem, with different coordinates.</li>
 * </ul>
 *
 * <h2>The action-bar wording is UNVERIFIED</h2>
 * Nothing here has seen a real Mines of Divan action bar. {@link #TREASURE} is written against the
 * expected {@code TREASURE: 12.3m}. Three log lines under {@code [SBS][Detector]} are the probe that
 * settles it: every bar that mentions treasure but does not parse (once per distinct text), the first
 * parsed reading of each hunt with the player position, and every find with the last reading, where
 * it was taken and the block under the crosshair - which is what says whether the distance is 3D or
 * horizontal. See {@code docs/features/metal-detector.md}.
 */
public final class MetalDetectorTracker {

    private static final MetalDetectorTracker INSTANCE = new MetalDetectorTracker();

    /** The zone the hunt happens in, as {@code SkyBlockLocation} spells it. */
    public static final String ZONE = "Mines of Divan";

    /** Transient-waypoint source key, so the fix can be cleared without touching other waypoints. */
    private static final String WAYPOINT_SOURCE = "metal_detector";

    /**
     * UNVERIFIED. Expected: {@code TREASURE: 12.3m}. Deliberately loose about the label and the
     * separator, because the one thing worth being strict about is that a number followed by
     * {@code m} is present.
     */
    private static final Pattern TREASURE =
            Pattern.compile("(?i)treasure\\s*:?\\s*([0-9]+(?:\\.[0-9]+)?)\\s*m\\b");

    /** Anything on the bar that says "treasure" at all, for the diagnostic below. */
    private static final Pattern MENTIONS_TREASURE = Pattern.compile("(?i)treasure");

    private final MetalDetectorHunt hunt = new MetalDetectorHunt();
    private String lobby = "";
    private volatile MetalDetectorSolver.Fix fix;
    private String unparsedSeen = "";

    private MetalDetectorTracker() {
    }

    public static MetalDetectorTracker getInstance() {
        return INSTANCE;
    }

    private static boolean enabled() {
        return ConfigManager.getInstance().get().metalDetector.enabled;
    }

    /** The current best fix, or null when the readings cannot pin one yet. */
    public MetalDetectorSolver.Fix fix() {
        return fix;
    }

    /** How many readings are in the current set - what the HUD counts up. */
    public int sampleCount() {
        return hunt.samples().size();
    }

    /** Whether the helper should be doing anything at all right now. */
    public boolean active() {
        return enabled() && ZONE.equalsIgnoreCase(SkyBlockLocation.zone());
    }

    /**
     * Fed from {@link sbs.modid.client.ui.hud.logic.HypixelHudState#parseActionBar} - the mod's one
     * action-bar hook. Called for every bar update, so it leaves as early as it can.
     */
    public void onActionBar(String text) {
        if (text == null || !enabled()) {
            return;
        }
        if (!active()) {
            if (!hunt.isEmpty()) {
                reset("left the zone");
            }
            return;
        }
        Matcher matcher = TREASURE.matcher(text);
        if (!matcher.find()) {
            noteUnparsed(text);
            return;
        }
        double distance;
        try {
            distance = Double.parseDouble(matcher.group(1));
        } catch (NumberFormatException e) {
            return;
        }
        addSample(text, distance);
    }

    /** Records the reading against where the player is standing, when that pairing can be trusted. */
    private void addSample(String text, double distance) {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        String server = ServerWorldTime.serverName();
        if (server != null && !server.equals(lobby)) {
            reset("lobby changed");
            lobby = server;
        }
        Vec3 pos = player.position();
        if (hunt.noteReading(pos.x, pos.y, pos.z, distance)) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Detector] first reading of this hunt: \"{}\" "
                    + "-> {} m, player feet at {}", text, distance, fmt(pos));
        }
        boolean standingStill = player.getDeltaMovement().lengthSqr() < 1.0E-4;
        if (hunt.offer(pos.x, pos.y, pos.z, distance, standingStill)) {
            recompute();
        }
    }

    private void recompute() {
        fix = MetalDetectorSolver.solve(hunt.samples());
        publishWaypoint();
    }

    /**
     * Publishes the fix as a transient waypoint, or clears it.
     *
     * <p>Through {@link WaypointStore#setTransient} under this feature's own source key, so it can
     * never disturb a waypoint the player set by hand - and so that clearing it is one call rather
     * than a search through {@code all()}.
     */
    private void publishWaypoint() {
        MetalDetectorSolver.Fix current = fix;
        if (current == null || !ConfigManager.getInstance().get().metalDetector.waypoint) {
            WaypointStore.clearTransient(WAYPOINT_SOURCE);
            return;
        }
        BlockPos pos = BlockPos.containing(current.x(), current.y(), current.z());
        String label = current.heightIsDerived()
                ? "Treasure (depth estimated)" : "Treasure";
        WaypointStore.setTransient(WAYPOINT_SOURCE, List.of(
                new Waypoint(label, pos, WaypointStore.currentDimension(), WAYPOINT_SOURCE)));
    }

    /**
     * A {@code You found ... with your Metal Detector!} line, from {@link DivanTracker}. Logs the probe
     * line while the solver is on, then ends the hunt: that chest is gone, and the next readings
     * describe another one.
     */
    public void onFound(DivanChat.Event event, String line) {
        if (enabled()) {
            logFind(line);
        }
        if (hunt.onChat(event)) {
            fix = null;
            WaypointStore.clearTransient(WAYPOINT_SOURCE);
        }
    }

    /**
     * The find, with everything needed to tell a 3D distance from a horizontal one: the last reading
     * and where it was taken, the player, and the block under the crosshair (the block just dug, or
     * the one behind it). Both distances from the reading spot to that block are printed, so the
     * comparison is a glance at the log rather than arithmetic.
     */
    private void logFind(String line) {
        Minecraft minecraft = Minecraft.getInstance();
        var player = minecraft.player;
        if (player == null) {
            return;
        }
        MetalDetectorHunt.Reading last = hunt.lastReading();
        String target = "none";
        String compare = "";
        if (minecraft.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos block = hit.getBlockPos();
            target = block.toShortString();
            if (last != null) {
                double dx = block.getX() + 0.5 - last.x();
                double dy = block.getY() + 0.5 - last.y();
                double dz = block.getZ() + 0.5 - last.z();
                compare = String.format(Locale.ROOT, "; reading spot to crosshair block centre: "
                                + "3D %.2f, horizontal %.2f, dy %.2f",
                        Math.sqrt(dx * dx + dy * dy + dz * dz), Math.sqrt(dx * dx + dz * dz), dy);
            }
        }
        String reading = last == null ? "none"
                : String.format(Locale.ROOT, "%.1f m taken at (%.2f, %.2f, %.2f)",
                        last.distance(), last.x(), last.y(), last.z());
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Detector] find: \"{}\" - player feet at {}, last "
                + "reading {}, crosshair block {}{}", line, fmt(player.position()), reading, target, compare);
    }

    /** Drops every reading and the fix with them. */
    public void reset(String why) {
        if (!hunt.isEmpty() || fix != null) {
            SkyblockSimplifiedSBS.LOGGER.debug("[SBS][Detector] reset ({}): {} sample(s) dropped",
                    why, hunt.samples().size());
        }
        hunt.clear();
        fix = null;
        WaypointStore.clearTransient(WAYPOINT_SOURCE);
    }

    /**
     * One log line per distinct unreadable bar that mentions treasure. Walk a mine for a few seconds
     * and the real text is in {@code latest.log}, whatever it turns out to say.
     */
    private void noteUnparsed(String text) {
        if (!MENTIONS_TREASURE.matcher(text).find() || text.equals(unparsedSeen)) {
            return;
        }
        unparsedSeen = text;
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Detector] action bar mentions treasure but did not parse - the expected "
                        + "wording is unverified. Seen: \"{}\"", text);
    }

    private static String fmt(Vec3 pos) {
        return String.format(Locale.ROOT, "(%.2f, %.2f, %.2f)", pos.x, pos.y, pos.z);
    }
}
