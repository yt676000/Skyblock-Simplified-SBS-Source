/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.experiment.logic;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Superpairs (memory / pairs) helper.
 *
 * <p><b>Mechanic.</b> A grid of covered tiles; clicking one reveals a reward, and two matching reveals
 * stay up while a mismatch flips both back to the cover. The cover is the same item in almost every
 * tile, so the "cover" is simply the most common tile on the board; anything else is a revealed reward.
 *
 * <p><b>Approach.</b> Every reward seen at a slot is remembered, and while that slot is covered again the
 * remembered icon is ghosted back onto it - so once you have peeked a tile you keep seeing what is under
 * it and can find its pair. There is deliberately no misclick guard here (the user asked to leave it out
 * of Superpairs).
 */
final class SuperpairsSolver {

    private static final Pattern ROUND = Pattern.compile("\\((?:Round\\s*)?([0-9]+)");

    /** Slot -> the reward last revealed there. */
    private final Map<Integer, ItemStack> revealed = new HashMap<>();

    private int round;
    /** Registry path of the cover tile (the board's most common item). */
    private String coverKey = "";

    void reset() {
        revealed.clear();
        round = 0;
        coverKey = "";
    }

    void scan(AbstractContainerMenu menu, int upper, String title) {
        int newRound = parseRound(title);
        if (newRound > 0 && newRound != round) {
            round = newRound;
            revealed.clear();
        }
        coverKey = modalItem(menu, upper);
        if (coverKey.isEmpty()) {
            return;
        }
        for (int i = 0; i < upper; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (stack == null || stack.isEmpty() || ExperimentationTable.isFiller(stack)) {
                continue;
            }
            if (!key(stack).equals(coverKey)) {
                revealed.put(i, stack.copyWithCount(1));   // a revealed reward (or a matched pair)
            }
        }
    }

    void render(GuiGraphicsExtractor g, Font font, AbstractContainerMenu menu, int left, int top) {
        if (coverKey.isEmpty()) {
            return;
        }
        for (Map.Entry<Integer, ItemStack> entry : revealed.entrySet()) {
            int idx = entry.getKey();
            if (idx < 0 || idx >= menu.slots.size()) {
                continue;
            }
            ItemStack live = menu.getSlot(idx).getItem();
            // Only ghost the memory back while the slot is covered again; a still-revealed / matched
            // tile shows its real icon already.
            if (live == null || live.isEmpty() || !key(live).equals(coverKey)) {
                continue;
            }
            Slot slot = menu.getSlot(idx);
            int x = left + slot.x;
            int y = top + slot.y;
            g.item(entry.getValue(), x, y);
            ExperimentationTable.outline(g, x, y, 0x66FFE24B);   // faint marker: this is a remembered peek
        }
    }

    /** The most common item's registry path among the playable, non-filler tiles. */
    private static String modalItem(AbstractContainerMenu menu, int upper) {
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < upper; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (stack == null || stack.isEmpty() || ExperimentationTable.isFiller(stack)) {
                continue;
            }
            counts.merge(key(stack), 1, Integer::sum);
        }
        String best = "";
        int bestCount = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > bestCount) {
                bestCount = entry.getValue();
                best = entry.getKey();
            }
        }
        return best;
    }

    String debug() {
        return "round=" + round + " cover=" + coverKey + " revealed=" + revealed.size();
    }

    private static String key(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static int parseRound(String title) {
        Matcher m = ROUND.matcher(title);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }
}
