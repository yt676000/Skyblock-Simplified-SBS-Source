/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.dev.scanner;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.dev.DevLogText;
import sbs.modid.client.core.dev.MenuReads;
import sbs.modid.client.core.item.SkyblockItem;

import java.util.ArrayList;
import java.util.List;

/**
 * Live {@link ItemStack} -> {@link ScanSlot}, on the client thread, at the hook. After this nothing
 * of the stack is kept, so a later packet that changes the stack cannot change a recorded value.
 */
final class ScanReads {

    private ScanReads() {
    }

    /** The slot record, or {@code null} for an empty stack. Never throws. */
    static ScanSlot read(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        try {
            Component name = stack.getHoverName();
            List<Component> loreLines = MenuReads.loreLines(stack);
            List<String> lore = new ArrayList<>(loreLines.size());
            for (Component line : loreLines) {
                lore.add(MenuReads.plain(line));
            }
            CompoundTag extra = SkyblockItem.extraAttributes(stack);
            return new ScanSlot(MenuReads.itemKey(stack), SkyblockItem.id(stack), MenuReads.plain(name),
                    DevLogText.legacy(name), lore, stack.getCount(), stack.hasFoil(),
                    extra.isEmpty() ? null : extra.toString());
        } catch (Throwable t) {
            // One unreadable stack must not cost the rest of the packet: record what is certain.
            return new ScanSlot(MenuReads.itemKey(stack), null, "<read failed: " + t.getClass().getSimpleName()
                    + ">", "", List.of(), stack.getCount(), false, null);
        }
    }

    /** {@link #read} for every stack of a list, index for index. */
    static ScanSlot[] readAll(List<ItemStack> stacks) {
        ScanSlot[] out = new ScanSlot[stacks.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = read(stacks.get(i));
        }
        return out;
    }
}
