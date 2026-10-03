/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.inventory.logic;

import java.util.ArrayList;
import java.util.List;

/**
 * Which slots a double-click collect ({@code ContainerInput.PICKUP_ALL}) would actually take from.
 *
 * <p>A replay of the loop in {@code AbstractContainerMenu.doClick}, kept free of Minecraft types so
 * it can be tested: two passes over the menu in click order (button 0 forwards, otherwise
 * backwards), the first skipping full stacks, both stopping the moment the cursor is full. That
 * order is the whole point - vanilla fills the cursor from partial stacks first, so a matching
 * stack in a locked slot is very often never reached, and refusing the gather just because one
 * exists refuses a click that would not have touched it.
 *
 * <p>The server runs the same loop on Hypixel, and the client cannot tell it to skip a slot, so the
 * answer here can only ever decide "send" or "refuse". It is exact as long as the client's view of
 * the menu matches the server's, which is the same assumption vanilla's own prediction makes.
 */
public final class PickAllSweep {

    /**
     * One menu slot as the sweep sees it.
     *
     * @param eligible     vanilla's per-slot test: has an item, same item and components as the
     *                     cursor, may be picked up, and the menu allows pick-all from it
     * @param count        the stack size in the slot
     * @param maxStackSize the stack's own maximum, which pass 0 uses to recognise a full stack
     */
    public record Candidate(boolean eligible, int count, int maxStackSize) {
    }

    private PickAllSweep() {
    }

    /**
     * The indices, in {@code slots}, the sweep takes at least one item from, in the order it takes
     * them. A slot is never taken from in both passes: pass 0 only leaves one part-full by filling
     * the cursor, which ends the sweep.
     *
     * @param carriedCount the cursor's stack size before the sweep
     * @param carriedMax   the cursor's maximum stack size
     * @param button       the click's button: 0 sweeps from the first slot, anything else from the last
     */
    public static List<Integer> drained(int carriedCount, int carriedMax, int button, List<Candidate> slots) {
        List<Integer> taken = new ArrayList<>();
        if (carriedCount <= 0) {
            return taken;   // vanilla does nothing without an item on the cursor
        }
        int size = slots.size();
        int[] remaining = new int[size];
        for (int i = 0; i < size; i++) {
            remaining[i] = slots.get(i).count();
        }
        int start = button == 0 ? 0 : size - 1;
        int step = button == 0 ? 1 : -1;
        int carried = carriedCount;
        for (int pass = 0; pass < 2; pass++) {
            for (int i = start; i >= 0 && i < size && carried < carriedMax; i += step) {
                Candidate slot = slots.get(i);
                if (!slot.eligible() || remaining[i] <= 0) {
                    continue;
                }
                if (pass == 0 && remaining[i] == slot.maxStackSize()) {
                    continue;   // full stacks are only raided once every partial one is used up
                }
                int take = Math.min(remaining[i], carriedMax - carried);
                remaining[i] -= take;
                carried += take;
                if (take > 0) {
                    taken.add(i);
                }
            }
        }
        return taken;
    }
}
