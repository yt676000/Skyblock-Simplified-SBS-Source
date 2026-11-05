/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.sacrifice.logic;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What left the inventory between two reads of it. Pure and generic over the stack type, so the
 * rule is testable with strings in place of {@code ItemStack}s.
 *
 * <p>A slot that emptied, changed to another item or shrank lost something. A key that appeared
 * or grew in any other slot in the same read was moved, not lost, and is dropped - otherwise
 * shuffling the inventory next to the altar would look like a sacrifice.
 */
public final class SlotDiff {

    /** One slot: its name key ({@code ""} when empty), how many, and the stack itself. */
    public record Slot<T>(String key, int count, T stack) {

        public boolean empty() {
            return key == null || key.isEmpty() || count <= 0;
        }
    }

    /** Something that left: the stack as it was before, and how many of it went. */
    public record Removed<T>(String key, int count, T stack) {
    }

    private SlotDiff() {
    }

    public static <T> List<Removed<T>> removed(List<Slot<T>> before, List<Slot<T>> after) {
        int size = Math.min(before.size(), after.size());
        Set<String> gained = new HashSet<>();
        List<Removed<T>> lost = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            Slot<T> was = before.get(i);
            Slot<T> now = after.get(i);
            boolean sameKey = !was.empty() && !now.empty() && was.key().equals(now.key());
            if (!now.empty() && (!sameKey || now.count() > was.count())) {
                gained.add(now.key());
            }
            if (was.empty()) {
                continue;
            }
            if (!sameKey) {
                lost.add(new Removed<>(was.key(), was.count(), was.stack()));
            } else if (now.count() < was.count()) {
                lost.add(new Removed<>(was.key(), was.count() - now.count(), was.stack()));
            }
        }
        lost.removeIf(r -> gained.contains(r.key()));
        return lost;
    }
}
