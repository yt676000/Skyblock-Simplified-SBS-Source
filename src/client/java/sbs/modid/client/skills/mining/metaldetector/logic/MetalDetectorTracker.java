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
import net.minecraft.world.phys.Vec3;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.pathfinding.Waypoint;
import sbs.modid.client.core.pathfinding.WaypointStore;
import sbs.modid.client.helper.timers.ServerWorldTime;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The game-facing half of the Metal Detector helper: readings in, a waypoint out.
 *
 * <p>{@link MetalDetectorSolver} does the maths and knows nothing about Minecraft; this collects
 * what it needs and decides when to throw it away. Those two jobs are separated because only one of
 * them can be tested - a reading needs a player in a world.
 *
 * <h2>Keeping the sample set honest</h2>
 * Every sample is a promise that the player was <i>there</i> when the detector said <i>that</i>, and
 * three things break the promise:
 * <ul>
 *   <li><b>Moving.</b> The action bar updates on its own schedule, so a reading taken mid-sprint
 *       pairs a fresh position with a stale distance. Samples are only kept from a standstill, or
 *       at least {@link MetalDetectorSolver#MIN_SAMPLE_SPACING} blocks from the last one.</li>
 *   <li><b>A new treasure.</b> Dug one up and the readings now describe something else entirely;
 *       fitting both sets together lands a point between two treasures, which is a spot with
 *       nothing under it.</li>
 *   <li><b>A new lobby, or leaving the zone.</b> Same problem, with different coordinates.</li>
 * </ul>
 *
 * <h2>⚠ The action-bar wording is UNVERIFIED</h2>
 * Nothing here has seen a real Mines of Divan. {@link #TREASURE} is written against the expected
 * {@code TREASURE: 12.3m} and every action-bar line that mentions treasure but does not parse is
 * logged once under {@code [SBS][Detector]}, so the real wording can be read out of
 * {@code latest.log}. Until that lands this may never fire at all, which is why the feature ships
 * off. See {@code docs/features/metal-detector.md}.
 */
public final class MetalDetectorTracker {

    private static final MetalDetectorTracker INSTANCE = new MetalDetectorTracker();

    /** The zone the hunt happens in, as {@code SkyBlockLocation} spells it. */
    public static final String ZONE = "Mines of Divan";

    /** Transient-waypoint source key, so the fix can be cleared without touching other waypoints. */
    private static final String WAYPOINT_SOURCE = "metal_detector";

    /**
     * ⚠ UNVERIFIED. Expected: {@code TREASURE: 12.3m}. Deliberately loose about the label and the
     * separator, because the one thing worth being strict about is that a number followed by
     * {@code m} is present.
     */
    private static final Pattern TREASURE =
            Pattern.compile("(?i)treasure\\s*:?\\s*([0-9]+(?:\\.[0-9]+)?)\\s*m\\b");

    /** Anything on the bar that says "treasure" at all, for the diagnostic below. */
    private static final Pattern MENTIONS_TREASURE = Pattern.compile("(?i)treasure");

    private final List<MetalDetectorSolver.Sample> samples = new ArrayList<>();
    private String lobby = "";
    private double lastX = Double.NaN;
    private double lastY = Double.NaN;
    private double lastZ = Double.NaN;
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
        return samples.size();
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
            if (!samples.isEmpty()) {
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
        addSample(distance);
    }

    /**
     * Records the reading against where the player is standing, when that pairing can be trusted.
     *
     * <p>A player in motion is the case to refuse: the bar and the position update independently, so
     * a sample taken at a run pairs a distance from a moment ago with a position from now, and the
     * error goes straight into the fit as if it were signal.
     */
    private void addSample(double distance) {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        String server = ServerWorldTime.serverName();
        if (!server.equals(lobby)) {
            reset("lobby changed");
            lobby = server;
        }
        Vec3 pos = player.position();
        if (!Double.isNaN(lastX)) {
            double dx = pos.x - lastX;
            double dy = pos.y - lastY;
            double dz = pos.z - lastZ;
            double moved = Math.sqrt(dx * dx + dy * dy + dz * dz);
            boolean standingStill = player.getDeltaMovement().lengthSqr() < 1.0E-4;
            if (!standingStill && moved < MetalDetectorSolver.MIN_SAMPLE_SPACING) {
                return;   // mid-stride, and not far enough from the last reading to be worth it
            }
            if (moved < 0.05) {
                return;   // standing on the same block: a duplicate sphere adds nothing
            }
        }
        lastX = pos.x;
        lastY = pos.y;
        lastZ = pos.z;
        samples.add(new MetalDetectorSolver.Sample(pos.x, pos.y, pos.z, distance));
        // A set that has grown far past what it needs is mostly old readings, and old readings are
        // the ones most likely to belong to a treasure that has already been dug up.
        while (samples.size() > 12) {
            samples.remove(0);
        }
        recompute();
    }

    private void recompute() {
        fix = MetalDetectorSolver.solve(samples);
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

    /** New target: the readings so far describe something that is no longer there. */
    public void onTreasureFound() {
        reset("treasure found");
    }

    /** Drops every reading and the fix with them. */
    public void reset(String why) {
        if (!samples.isEmpty() || fix != null) {
            SkyblockSimplifiedSBS.LOGGER.debug("[SBS][Detector] reset ({}): {} sample(s) dropped",
                    why, samples.size());
        }
        samples.clear();
        fix = null;
        lastX = Double.NaN;
        lastY = Double.NaN;
        lastZ = Double.NaN;
        WaypointStore.clearTransient(WAYPOINT_SOURCE);
    }

    /**
     * One log line per distinct unreadable bar that mentions treasure. This is the whole of the
     * probe for the action-bar wording: walk a mine for a few seconds and the real text is in
     * {@code latest.log}, whatever it turns out to say.
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
}
