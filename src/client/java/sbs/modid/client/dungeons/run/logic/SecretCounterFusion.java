/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.run.logic;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * Turns Hypixel's two secret counters into "this many secrets were just picked up", once each.
 *
 * <p>One pickup moves <b>both</b> counters - the run-wide tab "Secrets Found: N" and the room's
 * action-bar "x/y Secrets" - and they arrive on different ticks (the action bar is near-instant, the
 * tab lags by about a second). A chest or lever the client already saw directly moves them too. Acting
 * on every rise would therefore hide two or three secrets per pickup, the wrong ones after the first.
 * So every rise first consumes an outstanding <i>credit</i>: one left by a direct detection, or by the
 * other counter's earlier rise for the same pickup. Only a rise with nothing to consume is a new,
 * otherwise-unseen pickup (an item, a bat, an essence). Credits expire after {@link #CREDIT_TICKS}, so a
 * counter that never follows cannot swallow a real pickup minutes later.
 *
 * <p>Pure: no Minecraft types, ticks are passed in.
 */
public final class SecretCounterFusion {

    /** How long one source's rise waits for the other source to report the same pickup. */
    static final int CREDIT_TICKS = 60;

    /** Expiry tick of every outstanding credit, per source, oldest first. */
    private final Deque<Long> tabCredits = new ArrayDeque<>();
    private final Deque<Long> barCredits = new ArrayDeque<>();

    private int lastTab = -1;
    private int lastBarFound = -1;
    private String lastBarRoom;

    /**
     * A secret was seen collected directly (chest opened, lever pulled): both counters will rise for
     * it, and neither rise may hide a second secret.
     */
    public void noteDirect(long tick) {
        tabCredits.addLast(tick + CREDIT_TICKS);
        barCredits.addLast(tick + CREDIT_TICKS);
    }

    /**
     * Feeds this tick's counter readings and returns how many pickups nothing else accounted for.
     *
     * @param tabFound the run-wide tab count, or {@code -1} when unknown
     * @param barFound the action bar's room count, or {@code -1} when the bar shows none
     * @param room     the room the bar reading belongs to; a change re-baselines the bar
     */
    public int onTick(long tick, int tabFound, int barFound, String room) {
        expire(tabCredits, tick);
        expire(barCredits, tick);
        int fresh = 0;

        if (tabFound >= 0 && lastTab >= 0 && tabFound > lastTab) {
            fresh += absorb(tabFound - lastTab, tabCredits, barCredits, tick);
        }
        if (tabFound >= 0) {
            lastTab = tabFound;
        }

        if (!Objects.equals(room, lastBarRoom)) {
            lastBarRoom = room;
            lastBarFound = -1; // a new room's bar starts its own count
        }
        if (barFound >= 0 && lastBarFound >= 0 && barFound > lastBarFound) {
            fresh += absorb(barFound - lastBarFound, barCredits, tabCredits, tick);
        }
        if (barFound >= 0) {
            lastBarFound = barFound;
        }
        return fresh;
    }

    /** Run over: forget baselines and credits. */
    public void reset() {
        tabCredits.clear();
        barCredits.clear();
        lastTab = -1;
        lastBarFound = -1;
        lastBarRoom = null;
    }

    /**
     * Spends {@code own} credits first; every rise left over is a new pickup and leaves a credit for
     * the {@code other} counter, which will report the same pickup shortly.
     */
    private static int absorb(int rises, Deque<Long> own, Deque<Long> other, long tick) {
        int fresh = 0;
        for (int i = 0; i < rises; i++) {
            if (!own.isEmpty()) {
                own.removeFirst();
            } else {
                fresh++;
                other.addLast(tick + CREDIT_TICKS);
            }
        }
        return fresh;
    }

    private static void expire(Deque<Long> credits, long tick) {
        while (!credits.isEmpty() && credits.peekFirst() < tick) {
            credits.removeFirst();
        }
    }
}
