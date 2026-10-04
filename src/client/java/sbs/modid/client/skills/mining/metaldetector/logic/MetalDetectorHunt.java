/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.metaldetector.logic;

import java.util.ArrayList;
import java.util.List;

/**
 * The readings that belong to one buried chest. Pure, so the rules for what goes in and what ends a
 * hunt can be tested without a player: {@link MetalDetectorTracker} supplies the position and the
 * distance, this decides whether they become a sample.
 *
 * <p>A hunt ends when the detector digs something up. The chat says so on every find - a scavenged
 * tool or ordinary loot alike, both {@code You found ... with your Metal Detector!}, CONFIRMED - and
 * from then on the readings point at a different chest. Keeping the old ones would fit a point between
 * two chests, which is a spot with nothing under it.
 */
public final class MetalDetectorHunt {

    /** A set that has grown past this is mostly old readings. */
    static final int MAX_SAMPLES = 12;

    /** The most recent parsed reading and where it was taken, kept for the probe log. */
    public record Reading(double x, double y, double z, double distance) {
    }

    private final List<MetalDetectorSolver.Sample> samples = new ArrayList<>();
    private double lastX = Double.NaN;
    private double lastY = Double.NaN;
    private double lastZ = Double.NaN;
    private Reading lastReading;

    /** The samples the solver should fit. Live view; do not keep it. */
    public List<MetalDetectorSolver.Sample> samples() {
        return samples;
    }

    /** The last reading parsed in this hunt, kept or not; {@code null} before the first. */
    public Reading lastReading() {
        return lastReading;
    }

    /**
     * Notes a parsed reading, before deciding whether it becomes a sample.
     *
     * @return whether it is the first reading of this hunt - the probe logs that one
     */
    public boolean noteReading(double x, double y, double z, double distance) {
        boolean first = lastReading == null;
        lastReading = new Reading(x, y, z, distance);
        return first;
    }

    /**
     * Offers a reading as a sample.
     *
     * <p>A player in motion is the case to refuse: the bar and the position update independently, so
     * a sample taken at a run pairs a distance from a moment ago with a position from now, and the
     * error goes straight into the fit as if it were signal.
     *
     * @return whether it was kept
     */
    public boolean offer(double x, double y, double z, double distance, boolean standingStill) {
        if (!Double.isNaN(lastX)) {
            double dx = x - lastX;
            double dy = y - lastY;
            double dz = z - lastZ;
            double moved = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (!standingStill && moved < MetalDetectorSolver.MIN_SAMPLE_SPACING) {
                return false;   // mid-stride, and not far enough from the last reading to be worth it
            }
            if (moved < 0.05) {
                return false;   // the same spot: a duplicate sphere adds nothing
            }
        }
        lastX = x;
        lastY = y;
        lastZ = z;
        samples.add(new MetalDetectorSolver.Sample(x, y, z, distance));
        while (samples.size() > MAX_SAMPLES) {
            samples.remove(0);
        }
        return true;
    }

    /**
     * A chat event. Anything the detector dug up ends the hunt.
     *
     * @return whether the hunt ended
     */
    public boolean onChat(DivanChat.Event event) {
        if (event instanceof DivanChat.ToolFound || event instanceof DivanChat.ChestLoot) {
            clear();
            return true;
        }
        return false;
    }

    public boolean isEmpty() {
        return samples.isEmpty() && lastReading == null;
    }

    /** Drops every reading. */
    public void clear() {
        samples.clear();
        lastX = Double.NaN;
        lastY = Double.NaN;
        lastZ = Double.NaN;
        lastReading = null;
    }
}
