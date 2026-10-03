/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.diana.logic;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * The last inputs the Diana toolkit was handed, kept always - not only while Diana Log Mode runs - so
 * the guard's error report can say what led up to a failure even when nobody was recording.
 *
 * <p><b>What counts.</b> Exactly what a guarded hook receives: the chat line, the dug block, the
 * particle packet, the hand of an ability use, a world change, a click on the Sphinx's answer. Those
 * are the toolkit's inputs, so they are the Diana-relevant events by definition. The tick and the HUD
 * pass are not recorded; they carry no input and would push everything else out.
 *
 * <p><b>Two rings.</b> Particle packets arrive by the hundred, chat lines by the handful; one shared
 * ring would hold nothing but particles a second after the Hub loaded. Particles get one ring of
 * {@value #SIZE}, everything else another, and a dump merges both by time - the last
 * {@value #SIZE} of each, the "last 200 events" of the brief.
 *
 * <p><b>Free while nothing fails.</b> A slot is a time, a hook and a reference to the input the hook
 * already had, written into preallocated arrays: no allocation per entry. The inputs are immutable -
 * strings, block positions, packets - and only turned into text when a dump is asked for. Client
 * thread only, like every hook that feeds it.
 */
public final class DianaRecent {

    /** Entries per ring. */
    public static final int SIZE = 100;

    private static final Ring PARTICLES = new Ring();
    private static final Ring EVENTS = new Ring();

    /** Orders entries across the two rings; two inputs can share a millisecond. */
    private static long order;

    private DianaRecent() {
    }

    /** One hook call. Called by {@link DianaGuard} before it runs the hook's body. */
    public static void record(DianaGuard.Hook hook, Object input) {
        switch (hook) {
            case TICK, HUD, CHAT_HIDE -> {
                // No input worth keeping, and CHAT_HIDE is the same line CHAT already recorded.
            }
            case PARTICLES -> PARTICLES.add(hook, input);
            default -> EVENTS.add(hook, input);
        }
    }

    /**
     * Both rings, oldest first, as {@code {"t", "hook", "input"}} objects. {@code describe} turns an
     * input into text; it runs here, on the client thread, at the moment of the dump.
     */
    public static JsonArray dump(Function<Object, String> describe) {
        List<Entry> all = new ArrayList<>(2 * SIZE);
        PARTICLES.copyInto(all);
        EVENTS.copyInto(all);
        all.sort(Comparator.comparingLong(Entry::order));
        JsonArray out = new JsonArray();
        for (Entry entry : all) {
            JsonObject e = new JsonObject();
            e.addProperty("t", entry.t());
            e.addProperty("hook", entry.hook().name());
            String text;
            try {
                text = describe.apply(entry.input());
            } catch (RuntimeException | LinkageError failure) {
                text = "<unreadable: " + failure + ">";
            }
            e.addProperty("input", text);
            out.add(e);
        }
        return out;
    }

    /** Empties both rings - for tests. */
    public static void clear() {
        PARTICLES.clear();
        EVENTS.clear();
    }

    private record Entry(long order, long t, DianaGuard.Hook hook, Object input) {
    }

    /** A fixed ring of parallel arrays; the oldest entry is overwritten. */
    private static final class Ring {
        private final long[] orders = new long[SIZE];
        private final long[] times = new long[SIZE];
        private final DianaGuard.Hook[] hooks = new DianaGuard.Hook[SIZE];
        private final Object[] inputs = new Object[SIZE];
        private int next;

        void add(DianaGuard.Hook hook, Object input) {
            int slot = next;
            next = (slot + 1) % SIZE;
            orders[slot] = ++order;
            times[slot] = System.currentTimeMillis();
            hooks[slot] = hook;
            inputs[slot] = input;
        }

        void copyInto(List<Entry> out) {
            for (int i = 0; i < SIZE; i++) {
                if (hooks[i] != null) {
                    out.add(new Entry(orders[i], times[i], hooks[i], inputs[i]));
                }
            }
        }

        void clear() {
            java.util.Arrays.fill(hooks, null);
            java.util.Arrays.fill(inputs, null);
            next = 0;
        }
    }
}
