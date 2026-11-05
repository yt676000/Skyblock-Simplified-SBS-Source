/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.item;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A per-stack cache for a value derived from an item's components - the {@code StackTips} pattern,
 * made general and made safe against in-place mutation.
 *
 * <p><b>Keyed by stack identity, validated by component identity.</b> A menu usually hands a slot a
 * new {@link ItemStack} when its content changes, but not always: Hypixel updates a timer line or a
 * count on the stack it already sent, and {@code ItemStack.set} swaps the component value inside the
 * same stack. Every component value ({@code ItemLore}, a name {@code Component}, {@code CustomData})
 * is immutable, so a changed one is always a <i>new object</i>. Each entry therefore remembers the
 * exact component instances it was computed from, and a lookup compares them by reference - a few
 * map reads, no string work - and recomputes on any mismatch.
 *
 * <p>A {@code null} result is cached like any other (the entry exists, its value is null), so a
 * stack with no answer is not recomputed every frame either.
 *
 * <p>Bounded: cleared wholesale once it holds {@code cap} stacks, and every cache is cleared when a
 * screen opens or closes ({@link #clearAll}), so a closed menu's stacks are not kept alive.
 *
 * <p><b>Render/client thread only.</b> Not synchronised, and reading a live stack's components from
 * another thread is a race in its own right.
 */
public final class StackCache<V> {

    private static final List<StackCache<?>> ALL = new ArrayList<>();

    private final int cap;
    private final DataComponentType<?>[] inputs;
    private final Function<ItemStack, V> compute;
    private final Map<ItemStack, Entry<V>> entries = new IdentityHashMap<>();

    private record Entry<V>(Object[] stamp, V value) {
    }

    /**
     * @param inputs every component {@code compute} reads - a component missing from this list is
     *               one whose change the cache will not notice
     */
    @SafeVarargs
    public StackCache(int cap, Function<ItemStack, V> compute, DataComponentType<?>... inputs) {
        this.cap = cap;
        this.compute = compute;
        this.inputs = inputs;
        ALL.add(this);
    }

    /** The cached value for this stack, recomputed if any input component changed since. */
    public V get(ItemStack stack) {
        Entry<V> entry = entries.get(stack);
        if (entry != null && fresh(stack, entry.stamp)) {
            return entry.value;
        }
        if (entry == null && entries.size() >= cap) {
            entries.clear();
        }
        Object[] stamp = new Object[inputs.length];
        for (int i = 0; i < inputs.length; i++) {
            stamp[i] = stack.get(inputs[i]);
        }
        V value = compute.apply(stack);
        entries.put(stack, new Entry<>(stamp, value));
        return value;
    }

    private boolean fresh(ItemStack stack, Object[] stamp) {
        for (int i = 0; i < inputs.length; i++) {
            if (stack.get(inputs[i]) != stamp[i]) {
                return false;
            }
        }
        return true;
    }

    /** Drops every entry of every cache - called on each screen change. */
    public static void clearAll() {
        for (StackCache<?> cache : ALL) {
            cache.entries.clear();
        }
    }
}
