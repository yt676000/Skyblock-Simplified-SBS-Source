/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.farming.logic;

import sbs.modid.client.skills.farming.model.Farm;
import sbs.modid.client.skills.farming.model.Lane;
import sbs.modid.client.skills.farming.model.LaneArea;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntBinaryOperator;
import java.util.function.IntSupplier;

/**
 * The game-free farm maths of the Lane End Warning: which lane a position is in, repeating a lane
 * sideways, which farm is "current", and migrating the old one-rectangle lane areas. Nothing here
 * touches the game, so all of it is unit-tested ({@code LaneFarmsTest}).
 */
public final class LaneFarms {

    /** Most copies one {@code repeat} makes. */
    public static final int MAX_REPEAT = 64;
    /** Widest spacing one {@code repeat} accepts: a plot's width. */
    public static final int MAX_SPACING = 96;

    private LaneFarms() {
    }

    /** A lane the player stands in, with the farm holding it and its 0-based index there. */
    public record Hit(Farm farm, Lane lane, int index) {
    }

    /**
     * The lane containing a position, or {@code null}. Where lanes overlap (wide lanes marked close
     * together) the one whose middle is nearest across wins, then the first marked.
     */
    public static Hit laneAt(List<Farm> farms, double x, double z) {
        Hit best = null;
        double bestOff = Double.MAX_VALUE;
        for (Farm farm : farms) {
            for (int i = 0; i < farm.lanes.size(); i++) {
                Lane lane = farm.lanes.get(i);
                if (lane == null || !lane.contains(x, z)) {
                    continue;
                }
                double off = Math.abs(lane.cross(x, z) - lane.crossCentre());
                if (off < bestOff - 1e-9) {
                    bestOff = off;
                    best = new Hit(farm, lane, i);
                }
            }
        }
        return best;
    }

    /**
     * {@code count} copies of {@code lane}, the k-th moved {@code k * spacing} blocks across it
     * (a negative spacing goes the other way). Same ends, same width.
     */
    public static List<Lane> repeat(Lane lane, int count, int spacing) {
        List<Lane> out = new ArrayList<>(Math.max(0, count));
        for (int k = 1; k <= count; k++) {
            out.add(lane.shifted(k * spacing));
        }
        return out;
    }

    /** Why {@code repeat} would refuse these numbers, or {@code null} when they are fine. */
    public static String repeatProblem(int count, int spacing) {
        if (count < 1 || count > MAX_REPEAT) {
            return "Count must be 1 to " + MAX_REPEAT + ".";
        }
        if (spacing == 0 || Math.abs(spacing) > MAX_SPACING) {
            return "Spacing must be 1 to " + MAX_SPACING + " blocks (negative for the other side).";
        }
        return null;
    }

    /**
     * The farm marking goes into: among the farms on the plot you stand on, the selected one if it is
     * there, else the first; off every farm's plot, the selected one; else {@code null}.
     */
    public static Farm current(List<Farm> farms, int plotHere, int selectedId) {
        Farm selected = null;
        Farm firstHere = null;
        for (Farm farm : farms) {
            if (farm.id == selectedId) {
                selected = farm;
            }
            if (firstHere == null && plotHere > 0 && farm.plot == plotHere) {
                firstHere = farm;
            }
        }
        if (firstHere == null) {
            return selected;
        }
        return selected != null && selected.plot == plotHere ? selected : firstHere;
    }

    /** The farm with that name, ignoring case, or {@code null}. */
    public static Farm named(List<Farm> farms, String name) {
        String wanted = name == null ? "" : name.trim();
        for (Farm farm : farms) {
            if (farm.name.equalsIgnoreCase(wanted)) {
                return farm;
            }
        }
        return null;
    }

    /** {@code base}, or {@code base 2}, {@code base 3}... - the first name no farm has yet. */
    public static String freeName(List<Farm> farms, String base) {
        if (named(farms, base) == null) {
            return base;
        }
        for (int n = 2; ; n++) {
            String candidate = base + " " + n;
            if (named(farms, candidate) == null) {
                return candidate;
            }
        }
    }

    /**
     * The old lane areas as farms: each area becomes a farm of its own name holding one
     * {@linkplain Lane#rows rectangle lane group} with the same footprint and the axis the area
     * resolved to, so every edge that warned before still warns. {@code plotAt} maps a block
     * {@code (x, z)} to its plot number and is asked for the area's middle.
     */
    public static List<Farm> fromAreas(List<LaneArea> areas, IntSupplier nextId, IntBinaryOperator plotAt) {
        List<Farm> farms = new ArrayList<>();
        for (LaneArea area : areas) {
            if (area == null) {
                continue;
            }
            Lane.Axis axis = area.axis() == LaneArea.Axis.Z ? Lane.Axis.Z : Lane.Axis.X;
            Lane lane = Lane.rows(area.minX(), area.minZ(), area.maxX(), area.maxZ(), area.minY(), axis);
            String base = area.name == null || area.name.isBlank() ? "Farm" : area.name.trim();
            Farm farm = new Farm(nextId.getAsInt(), freeName(farms, base),
                    plotAt.applyAsInt((area.minX() + area.maxX()) / 2, (area.minZ() + area.maxZ()) / 2));
            farm.lanes.add(lane);
            farms.add(farm);
        }
        return farms;
    }
}
