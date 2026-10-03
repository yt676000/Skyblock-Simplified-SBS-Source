/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.build.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The timeline behind {@code //undo}, {@code //redo} and the timeline screen: every applied edit
 * in order, and a cursor at the one the world currently reflects.
 *
 * <p>Timeline, not just a stack: {@link #stepsTo} answers "what has to be replayed, in which
 * direction, to get the world to entry N" for any N, so the screen can jump straight back to 14:03
 * instead of undoing one step at a time. A new edit made after undoing drops the undone tail, as in
 * any editor - the history is a line, never a tree.
 *
 * <p>Capped by total cells, not entry count: one 4M-block edit weighs more than a hundred small ones.
 * Past the cap the oldest entries fall off and {@link #droppedCount()} says how many, so the screen
 * can tell the player the timeline has a beginning they cannot reach.
 *
 * <p>Pure: the record type is whatever the engine stores, and replaying it is the engine's job.
 *
 * @param <R> what one entry carries (the edit's before/after data)
 */
public final class EditHistory<R extends EditHistory.Sized> {

    /** Anything that can say how many cells it holds. */
    public interface Sized {
        long cells();
    }

    /** One timeline entry. */
    public record Entry<R>(String name, long time, long blocks, R record) {
    }

    /** One replay step: which entry, and whether to go back (undo) or forward (redo). */
    public record Step<R>(Entry<R> entry, boolean backward) {
    }

    private final List<Entry<R>> entries = new ArrayList<>();
    private final long maxCells;
    private long totalCells;
    private int dropped;

    /** Index of the last applied entry; -1 = before the first. */
    private int cursor = -1;

    public EditHistory(long maxCells) {
        this.maxCells = maxCells;
    }

    public List<Entry<R>> entries() {
        return Collections.unmodifiableList(entries);
    }

    /** Index of the entry the world reflects now, -1 when everything is undone. */
    public int cursor() {
        return cursor;
    }

    /** How many old entries fell off the front past the cell cap. */
    public int droppedCount() {
        return dropped;
    }

    public boolean canUndo() {
        return cursor >= 0;
    }

    public boolean canRedo() {
        return cursor < entries.size() - 1;
    }

    /** Records a freshly applied edit; anything undone after the cursor is discarded first. */
    public void push(Entry<R> entry) {
        while (entries.size() > cursor + 1) {
            Entry<R> removed = entries.remove(entries.size() - 1);
            totalCells -= removed.record().cells();
        }
        entries.add(entry);
        totalCells += entry.record().cells();
        cursor = entries.size() - 1;
        // Keep the newest entry even when it alone is over the cap - it is the one being undone next.
        while (totalCells > maxCells && entries.size() > 1) {
            Entry<R> removed = entries.remove(0);
            totalCells -= removed.record().cells();
            cursor--;
            dropped++;
        }
    }

    /**
     * The steps to move the world from the cursor to {@code target} (-1 = before everything):
     * backward steps newest first, forward steps oldest first. Empty when already there.
     */
    public List<Step<R>> stepsTo(int target) {
        int clamped = Math.max(-1, Math.min(entries.size() - 1, target));
        List<Step<R>> steps = new ArrayList<>();
        if (clamped < cursor) {
            for (int i = cursor; i > clamped; i--) {
                steps.add(new Step<>(entries.get(i), true));
            }
        } else {
            for (int i = cursor + 1; i <= clamped; i++) {
                steps.add(new Step<>(entries.get(i), false));
            }
        }
        return steps;
    }

    /** The steps for {@code n} undos. */
    public List<Step<R>> undoSteps(int n) {
        return stepsTo(cursor - Math.max(1, n));
    }

    /** The steps for {@code n} redos. */
    public List<Step<R>> redoSteps(int n) {
        return stepsTo(cursor + Math.max(1, n));
    }

    /** Marks the world as now reflecting entry {@code target}, once its steps have been replayed. */
    public void moveCursor(int target) {
        cursor = Math.max(-1, Math.min(entries.size() - 1, target));
    }

    /** The index of {@code entry}, or -1. */
    public int indexOf(Entry<R> entry) {
        return entries.indexOf(entry);
    }

    public void clear() {
        entries.clear();
        totalCells = 0;
        dropped = 0;
        cursor = -1;
    }
}
