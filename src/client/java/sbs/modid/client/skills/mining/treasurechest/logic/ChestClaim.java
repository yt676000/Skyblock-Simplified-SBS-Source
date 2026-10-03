/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.treasurechest.logic;

import sbs.modid.client.skills.mining.treasurechest.model.TreasureChestSignals;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

/**
 * Pairs the spawn line with the chest block it announced.
 *
 * <p>Neither half is enough alone. A chest block appearing could be anybody's - another player
 * mining two blocks over uncovers chests too - and the line carries no position at all. Only the
 * pair is trusted: a chest that appeared within {@link TreasureChestSignals#CLAIM_RADIUS} of the
 * player and {@link TreasureChestSignals#CLAIM_WINDOW_MS} of the line.
 *
 * <p>Either may arrive first - the chat and block packets are separate, and which one Hypixel sends
 * first is not known - so appearances are remembered for the window, and a line with nothing to
 * pair yet waits for the window.
 *
 * <p>Pure: positions and a clock in, a claimed position out. No Minecraft, so it can be tested.
 */
public final class ChestClaim {

    /** A chest block that appeared, and when. */
    public record Appearance(int x, int y, int z, long atMs) {
        double distanceTo(double px, double py, double pz) {
            double dx = x + 0.5 - px;
            double dy = y + 0.5 - py;
            double dz = z + 0.5 - pz;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    private final Deque<Appearance> recent = new ArrayDeque<>();
    /** When an unpaired spawn line arrived, or {@code -1} while none is waiting. */
    private long pendingLineAt = -1L;

    /**
     * A chest block appeared at {@code (x, y, z)} with the player at {@code (px, py, pz)}.
     *
     * @return the appearance, claimed, when a spawn line was waiting for it; otherwise {@code null}
     *     and the appearance is remembered in case the line is still on its way
     */
    public Appearance onChestAppeared(int x, int y, int z, long now, double px, double py, double pz) {
        prune(now);
        Appearance appearance = new Appearance(x, y, z, now);
        if (appearance.distanceTo(px, py, pz) > TreasureChestSignals.CLAIM_RADIUS) {
            return null;   // too far to be the one you uncovered, now or later
        }
        if (pendingLineAt >= 0) {
            pendingLineAt = -1L;
            return appearance;
        }
        recent.addLast(appearance);
        while (recent.size() > 8) {
            recent.removeFirst();
        }
        return null;
    }

    /**
     * The spawn line arrived with the player at {@code (px, py, pz)}.
     *
     * @return the nearest chest that appeared in the window, claimed; or {@code null}, in which case
     *     the next appearance inside the window claims instead
     */
    public Appearance onSpawnLine(long now, double px, double py, double pz) {
        prune(now);
        Appearance best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Appearance appearance : recent) {
            double distance = appearance.distanceTo(px, py, pz);
            if (distance <= TreasureChestSignals.CLAIM_RADIUS && distance < bestDistance) {
                best = appearance;
                bestDistance = distance;
            }
        }
        if (best != null) {
            recent.remove(best);
            return best;
        }
        pendingLineAt = now;
        return null;
    }

    /**
     * Whether a spawn line has waited out the window without a chest. True once per such line - the
     * caller logs it, which is the signal that the block half of the pairing is wrong.
     */
    public boolean pendingExpired(long now) {
        if (pendingLineAt >= 0 && now - pendingLineAt > TreasureChestSignals.CLAIM_WINDOW_MS) {
            pendingLineAt = -1L;
            return true;
        }
        return false;
    }

    /** Lobby change, leaving the island: nothing remembered may pair across it. */
    public void clear() {
        recent.clear();
        pendingLineAt = -1L;
    }

    private void prune(long now) {
        Iterator<Appearance> it = recent.iterator();
        while (it.hasNext()) {
            if (now - it.next().atMs() > TreasureChestSignals.CLAIM_WINDOW_MS) {
                it.remove();
            }
        }
        if (pendingLineAt >= 0 && now - pendingLineAt > TreasureChestSignals.CLAIM_WINDOW_MS) {
            pendingLineAt = -1L;
        }
    }
}
