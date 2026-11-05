/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.puzzle.logic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Teleport Maze, pure: the pad layout and what the player's teleports say about the way out.
 *
 * <p><b>The layout.</b> The pads (end portal frames) sit in a 3 x 3 grid of cells, one cell every
 * {@link #CELL_PITCH} blocks, counted from the lowest pad corner. The centre cell holds the goal;
 * the cell the player enters through holds a single pad. Every other pad is a {@link Role#CHOICE}.
 * Nothing here knows absolute coordinates or the room's rotation - the cells are derived from the
 * pads themselves, so a rotated room reads the same.
 *
 * <p><b>The rule.</b> After every teleport the player lands facing the pad that leads to the goal.
 * Each landing is a ray; every unused choice pad is scored by the angle between that ray and the
 * direction from the landing spot to the pad, summed over all landings. The pad with the lowest
 * total is the prediction - <b>only</b> when it beats the runner-up by {@link #MARGIN_DEGREES}, so two
 * pads lying on one ray stay unresolved until a second landing separates them.
 *
 * <p>Pads stepped on or landed on are marked used: they demonstrably lead elsewhere.
 *
 * <p>Not thread-safe; owned by the client tick.
 */
public final class PadMaze {

    /** Blocks from one cell's origin to the next. */
    public static final int CELL_PITCH = 8;
    /** Cells per side. */
    public static final int GRID = 3;
    /** How far (horizontal, blocks) a position may be from a pad's centre and still be "on" it. */
    public static final double ON_PAD = 1.6;
    /** Lead the best total needs over the runner-up before it is shown. */
    public static final double MARGIN_DEGREES = 3.0;

    /** A pad, by block column. */
    public record Pad(int x, int z) {
        double cx() {
            return x + 0.5;
        }

        double cz() {
            return z + 0.5;
        }

        @Override
        public String toString() {
            return "(" + x + "," + z + ")";
        }
    }

    public enum Role { ENTRY, GOAL, CHOICE }

    /** What one teleport did, for the log line. */
    public record Hop(Pad from, Pad to, boolean reachedGoal, Pad prediction, double lead) {
    }

    private final List<Pad> pads;
    private final Map<Pad, Role> roles;
    private final Map<Pad, Integer> cellOf;
    private final Set<Pad> used = new LinkedHashSet<>();
    private final Map<Pad, Double> score = new HashMap<>();
    /** Pad stepped on -> the cell it carried the player to, learnt from this room's teleports. */
    private final Map<Pad, Integer> links = new HashMap<>();
    private int landings;
    private Pad prediction;
    private double lead;
    private boolean done;

    private PadMaze(List<Pad> pads, Map<Pad, Role> roles, Map<Pad, Integer> cellOf) {
        this.pads = pads;
        this.roles = roles;
        this.cellOf = cellOf;
    }

    /**
     * Reads a layout, or returns {@code null} when the pads do not form the grid: fewer than a handful,
     * spread wider than three cells, or no single goal cell. A {@code null} means the solver shows
     * nothing for this room.
     */
    public static PadMaze of(List<Pad> found) {
        if (found == null || found.size() < 6) {
            return null;
        }
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        for (Pad pad : found) {
            minX = Math.min(minX, pad.x());
            minZ = Math.min(minZ, pad.z());
        }
        Map<Pad, Integer> cellOf = new HashMap<>();
        int[] perCell = new int[GRID * GRID];
        for (Pad pad : found) {
            int cx = (pad.x() - minX) / CELL_PITCH;
            int cz = (pad.z() - minZ) / CELL_PITCH;
            if (cx >= GRID || cz >= GRID) {
                return null;
            }
            int cell = cx * GRID + cz;
            cellOf.put(pad, cell);
            perCell[cell]++;
        }
        int centre = GRID * GRID / 2;
        if (perCell[centre] == 0) {
            return null;
        }
        Map<Pad, Role> roles = new HashMap<>();
        for (Pad pad : found) {
            int cell = cellOf.get(pad);
            Role role = cell == centre ? Role.GOAL : perCell[cell] == 1 ? Role.ENTRY : Role.CHOICE;
            roles.put(pad, role);
        }
        return new PadMaze(List.copyOf(found), roles, cellOf);
    }

    public List<Pad> pads() {
        return pads;
    }

    public Role role(Pad pad) {
        return roles.get(pad);
    }

    /** Count of pads in each role, for the enter log line. */
    public Map<Role, Integer> roleCounts() {
        Map<Role, Integer> out = new java.util.EnumMap<>(Role.class);
        for (Role role : roles.values()) {
            out.merge(role, 1, Integer::sum);
        }
        return out;
    }

    /** The pad under a horizontal position, or {@code null}. */
    public Pad padAt(double x, double z) {
        Pad best = null;
        double bestDist = ON_PAD * ON_PAD;
        for (Pad pad : pads) {
            double dx = pad.cx() - x;
            double dz = pad.cz() - z;
            double d = dx * dx + dz * dz;
            if (d <= bestDist) {
                bestDist = d;
                best = pad;
            }
        }
        return best;
    }

    /**
     * Records one teleport. Ignored (returns {@code null}) when the landing is not on a pad - a jump
     * that is not a pad teleport says nothing about the maze.
     *
     * @param yaw the player's yaw on landing, Minecraft convention (0 = +Z, 90 = -X)
     */
    public Hop observe(double fromX, double fromZ, double toX, double toZ, float yaw) {
        Pad to = padAt(toX, toZ);
        if (to == null || done) {
            return null;
        }
        Pad from = padAt(fromX, fromZ);
        link(from, to);
        if (role(to) == Role.GOAL) {
            done = true;
            prediction = null;
            return new Hop(from, to, true, null, 0);
        }
        markUsed(from);
        markUsed(to);
        double rad = Math.toRadians(yaw);
        double lookX = -Math.sin(rad);
        double lookZ = Math.cos(rad);
        for (Pad pad : pads) {
            if (role(pad) != Role.CHOICE || pad.equals(to)) {
                continue;
            }
            double dx = pad.cx() - toX;
            double dz = pad.cz() - toZ;
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 1e-6) {
                continue;
            }
            double cos = Math.max(-1, Math.min(1, (dx * lookX + dz * lookZ) / len));
            score.merge(pad, Math.toDegrees(Math.acos(cos)), Double::sum);
        }
        landings++;
        predict();
        return new Hop(from, to, false, prediction, lead);
    }

    private void markUsed(Pad pad) {
        if (pad != null && role(pad) == Role.CHOICE) {
            used.add(pad);
        }
    }

    private void predict() {
        Pad best = null;
        double first = Double.MAX_VALUE;
        double second = Double.MAX_VALUE;
        for (Map.Entry<Pad, Double> entry : score.entrySet()) {
            if (used.contains(entry.getKey())) {
                continue;
            }
            double s = entry.getValue();
            if (s < first) {
                second = first;
                first = s;
                best = entry.getKey();
            } else if (s < second) {
                second = s;
            }
        }
        lead = second == Double.MAX_VALUE ? Double.MAX_VALUE : second - first;
        prediction = best != null && lead >= MARGIN_DEGREES ? best : null;
    }

    /** The pad predicted to lead to the goal, or {@code null} when not certain. */
    public Pad prediction() {
        return prediction;
    }

    public Set<Pad> used() {
        return Collections.unmodifiableSet(used);
    }

    public int landings() {
        return landings;
    }

    /** Whether the goal has been reached; nothing more is shown. */
    public boolean done() {
        return done;
    }

    /** Remembers that stepping on {@code from} lands in {@code to}'s cell. Directed: a pad's way back is its own. */
    void link(Pad from, Pad to) {
        if (from != null && to != null) {
            links.put(from, cellOf(to));
        }
    }

    /** The cell of the pad nearest to {@code (x, z)} - where the player stands after a landing. */
    public int cellAt(double x, double z) {
        Pad best = null;
        double bestDist = Double.MAX_VALUE;
        for (Pad pad : pads) {
            double dx = pad.cx() - x;
            double dz = pad.cz() - z;
            double d = dx * dx + dz * dz;
            if (d < bestDist) {
                bestDist = d;
                best = pad;
            }
        }
        return best == null ? -1 : cellOf(best);
    }

    /**
     * The shortest chain of pads already used whose teleports carry the player from {@code fromCell}
     * into {@code target}'s cell, in stepping order. Empty when already there, {@code null} when the
     * known links do not reach it. The full way can only be what this room has shown: where an
     * unused pad leads is unknown until someone steps on it (asked 2026-10-17: show the whole way).
     */
    public List<Pad> routeTo(int fromCell, Pad target) {
        int goal = cellOf(target);
        if (goal < 0 || fromCell < 0) {
            return null;
        }
        if (fromCell == goal) {
            return List.of();
        }
        Map<Integer, Pad> via = new HashMap<>();
        Map<Integer, Integer> prev = new HashMap<>();
        java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
        queue.add(fromCell);
        prev.put(fromCell, -1);
        while (!queue.isEmpty()) {
            int cell = queue.poll();
            for (Pad pad : pads) {
                Integer next = links.get(pad);
                if (next == null || cellOf(pad) != cell || prev.containsKey(next)) {
                    continue;
                }
                prev.put(next, cell);
                via.put(next, pad);
                if (next == goal) {
                    List<Pad> out = new ArrayList<>();
                    for (int c = goal; c != fromCell; c = prev.get(c)) {
                        out.add(0, via.get(c));
                    }
                    return out;
                }
                queue.add(next);
            }
        }
        return null;
    }

    /** Cell index ({@code x * 3 + z}) a pad sits in, for logging. */
    public int cellOf(Pad pad) {
        Integer cell = cellOf.get(pad);
        return cell == null ? -1 : cell;
    }

    /** Pads of one role, in scan order. */
    public List<Pad> padsOf(Role role) {
        List<Pad> out = new ArrayList<>();
        for (Pad pad : pads) {
            if (roles.get(pad) == role) {
                out.add(pad);
            }
        }
        return out;
    }
}
