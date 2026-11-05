/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.puzzle.logic;

/**
 * Ice Fill, the search: a walk over a grid that steps on every open cell exactly once and ends on a
 * given cell - a Hamiltonian path with both ends fixed. Pure, no Minecraft types.
 *
 * <p>Depth-first, fewest-onward-moves first, with two prunes that cut floors of this size to a few
 * hundred nodes: the unvisited cells must stay connected to the walker, and no unvisited cell other
 * than the goal may be left with a single way in (it would have to be the last step). Once a route
 * is found the search keeps going, within the node budget, for one with fewer turns - a route with
 * long straight runs is easier to walk without slipping onto the wrong tile.
 *
 * <p>Bounded by {@code budget} expanded nodes, so a floor with no route (a mis-picked exit, a tile
 * already walked wrong) costs a known, small amount on the tick it is planned.
 */
public final class IceFillRoute {

    /** Nodes for the first route; well above what a floor of up to ~60 cells needs. */
    public static final int FIND_BUDGET = 60_000;
    /** Further nodes spent looking for fewer turns. */
    public static final int IMPROVE_BUDGET = 20_000;

    /** A found route: cell indices from start to goal, its turn count, and the nodes it cost. */
    public record Result(int[] path, int turns, int nodes) {
    }

    private final int width;
    private final int depth;
    private final boolean[] open;
    private final int goal;
    private final int cellCount;

    private final boolean[] visited;
    private final int[] path;
    private final int[] queue;
    private final boolean[] seen;
    private int[] best;
    private int bestTurns = Integer.MAX_VALUE;
    private int nodes;
    private int limit;

    private IceFillRoute(int width, int depth, boolean[] open, int goal) {
        this.width = width;
        this.depth = depth;
        this.open = open;
        this.goal = goal;
        int count = 0;
        for (boolean b : open) {
            if (b) {
                count++;
            }
        }
        this.cellCount = count;
        this.visited = new boolean[open.length];
        this.path = new int[count];
        this.queue = new int[open.length];
        this.seen = new boolean[open.length];
    }

    /**
     * The route over every {@code open} cell from {@code start} to {@code goal}, or {@code null} when
     * there is none or the budget ran out first. Both ends must be open cells.
     */
    public static Result solve(int width, int depth, boolean[] open, int start, int goal) {
        if (start < 0 || goal < 0 || start >= open.length || goal >= open.length
                || !open[start] || !open[goal] || open.length != width * depth) {
            return null;
        }
        IceFillRoute search = new IceFillRoute(width, depth, open, goal);
        if (search.cellCount > 1 && start == goal) {
            return null;
        }
        search.visited[start] = true;
        search.path[0] = start;
        search.limit = FIND_BUDGET;
        search.dfs(start, 1, 0, 0);
        if (search.best == null) {
            return null;
        }
        search.limit = search.nodes + IMPROVE_BUDGET;
        search.dfs(start, 1, 0, 0);
        return new Result(search.best, search.bestTurns, search.nodes);
    }

    /** The number of direction changes along a route of cell indices. */
    public static int turns(int[] route) {
        int turns = 0;
        int last = 0;
        for (int i = 1; i < route.length; i++) {
            int step = route[i] - route[i - 1];
            if (i > 1 && step != last) {
                turns++;
            }
            last = step;
        }
        return turns;
    }

    private void dfs(int at, int length, int lastStep, int turns) {
        if (nodes >= limit || turns >= bestTurns) {
            return;
        }
        nodes++;
        if (length == cellCount) {
            if (at == goal) {
                bestTurns = turns;
                best = path.clone();
            }
            return;
        }
        if (at == goal || !promising(at, length)) {
            return;
        }
        // Up to four moves, ordered: fewest onward moves first, straight ahead before a turn.
        int[] moves = new int[4];
        int[] scores = new int[4];
        int n = 0;
        for (int d = 0; d < 4; d++) {
            int next = neighbour(at, d);
            if (next < 0 || visited[next]) {
                continue;
            }
            int step = next - at;
            int score = onward(next) * 2 + (step == lastStep ? 0 : 1);
            int k = n++;
            while (k > 0 && scores[k - 1] > score) {
                moves[k] = moves[k - 1];
                scores[k] = scores[k - 1];
                k--;
            }
            moves[k] = next;
            scores[k] = score;
        }
        for (int m = 0; m < n; m++) {
            int next = moves[m];
            int step = next - at;
            visited[next] = true;
            path[length] = next;
            dfs(next, length + 1, step, turns + (lastStep != 0 && step != lastStep ? 1 : 0));
            visited[next] = false;
            if (nodes >= limit) {
                return;
            }
        }
    }

    /** Whether the unvisited cells can still all be walked from {@code at}. */
    private boolean promising(int at, int length) {
        // A cell other than the goal with at most one way in is a dead end the walk cannot leave.
        for (int i = 0; i < open.length; i++) {
            if (!open[i] || visited[i] || i == goal) {
                continue;
            }
            int ways = 0;
            for (int d = 0; d < 4; d++) {
                int nb = neighbour(i, d);
                if (nb >= 0 && (!visited[nb] || nb == at)) {
                    ways++;
                }
            }
            if (ways < 2) {
                return false;
            }
        }
        // Everything unvisited is reachable from here without crossing the walked cells.
        java.util.Arrays.fill(seen, false);
        int head = 0;
        int tail = 0;
        queue[tail++] = at;
        seen[at] = true;
        int reached = 0;
        while (head < tail) {
            int c = queue[head++];
            for (int d = 0; d < 4; d++) {
                int nb = neighbour(c, d);
                if (nb >= 0 && !visited[nb] && !seen[nb]) {
                    seen[nb] = true;
                    queue[tail++] = nb;
                    reached++;
                }
            }
        }
        return reached == cellCount - length;
    }

    private int onward(int cell) {
        int count = 0;
        for (int d = 0; d < 4; d++) {
            int nb = neighbour(cell, d);
            if (nb >= 0 && !visited[nb]) {
                count++;
            }
        }
        return count;
    }

    /** The open neighbour in direction {@code d} (0 +x, 1 -x, 2 +z, 3 -z), or -1. */
    private int neighbour(int cell, int d) {
        int x = cell % width;
        int z = cell / width;
        int nb;
        switch (d) {
            case 0 -> nb = x + 1 < width ? cell + 1 : -1;
            case 1 -> nb = x > 0 ? cell - 1 : -1;
            case 2 -> nb = z + 1 < depth ? cell + width : -1;
            default -> nb = z > 0 ? cell - width : -1;
        }
        return nb >= 0 && open[nb] ? nb : -1;
    }
}
