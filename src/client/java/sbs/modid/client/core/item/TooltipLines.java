/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.item;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Tooltip lines a feature appends to a stack, built once per stack and data version instead of
 * once per frame while the stack is hovered.
 *
 * <p>The stack side is a {@link StackCache} (stack identity, validated against the name, lore and
 * custom-data components, cleared on every screen change). Everything else the lines depend on -
 * the {@link sbs.modid.client.economy.prices.PriceVersion}, the config revision, the stack count,
 * Shift - is packed by the caller into one {@code long} {@link #key}; a different key rebuilds.
 *
 * <p>The builder returns an immutable list, empty when the feature has nothing to say (cached like
 * any other answer). Render thread only.
 */
public final class TooltipLines {

    /** The last build for one stack. Render thread only. */
    static final class Slot {
        private boolean built;
        private long key;
        private List<Component> lines;

        /** The lines for {@code key}: the cached ones when it is unchanged, else a fresh build. */
        <S> List<Component> lines(long key, S stack, Function<S, List<Component>> build) {
            if (!built || this.key != key) {
                lines = build.apply(stack);
                this.key = key;
                built = true;
            }
            return lines;
        }
    }

    private final StackCache<Slot> slots;
    private final Function<ItemStack, List<Component>> build;

    public TooltipLines(Function<ItemStack, List<Component>> build) {
        this.build = build;
        this.slots = new StackCache<>(256, stack -> new Slot(),
                DataComponents.CUSTOM_DATA, DataComponents.LORE, DataComponents.CUSTOM_NAME,
                DataComponents.ITEM_NAME);
    }

    /** The cached lines for this stack under {@code key}. */
    public List<Component> get(ItemStack stack, long key) {
        return slots.get(stack).lines(key, stack, build);
    }

    /** {@code current} plus {@code extra} - {@code current} itself when there is nothing to add. */
    public static List<Component> append(List<Component> current, List<Component> extra) {
        if (extra.isEmpty()) {
            return current;
        }
        List<Component> out = new ArrayList<>(current.size() + extra.size());
        out.addAll(current);
        out.addAll(extra);
        return out;
    }

    /**
     * Packs the inputs into one key: {@code version} whole, the low 16 bits of {@code revision}
     * (a config revision) and of {@code extra} (count, Shift, a day number).
     */
    public static long key(int version, int revision, int extra) {
        return ((long) version << 32) | ((long) (revision & 0xFFFF) << 16) | (extra & 0xFFFF);
    }
}
