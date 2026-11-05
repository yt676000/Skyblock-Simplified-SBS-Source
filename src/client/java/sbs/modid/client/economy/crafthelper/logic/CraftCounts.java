/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.crafthelper.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.item.ItemAnalysis;
import sbs.modid.client.helper.sacks.logic.SackContents;
import sbs.modid.client.helper.storage.StorageIndex;
import sbs.modid.client.helper.storage.StorageSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * How many of each item the player has, from the sources the Craft Helper settings select.
 *
 * <p>Reads what already exists rather than a second index: the live inventory, and the snapshots
 * {@link StorageIndex} keeps of sacks, Ender Chest pages, backpacks, the vault and island chests.
 * Those snapshots are only as fresh as the last time each storage was open, and only exist for the
 * kinds the Storage Search settings index - a storage never opened counts as empty.
 *
 * <p>A sack slot's amount is in its lore, not its stack size, so it goes through
 * {@link SackContents#storedAmount}; when that lore is not understood the slot counts as its stack
 * size rather than as a confident zero.
 *
 * <p>Client thread only (live stacks). The stored part is cached against
 * {@link StorageIndex#revision()}, so a rebuild with no storage change walks the 36 inventory slots
 * and nothing else.
 */
final class CraftCounts {

    private static final int INVENTORY_SLOTS = 36;

    private static final StorageSource.Kind[] STORAGE_KINDS = {
            StorageSource.Kind.ENDER_CHEST, StorageSource.Kind.BACKPACK,
            StorageSource.Kind.VAULT, StorageSource.Kind.CHEST
    };

    private int storedRevision = Integer.MIN_VALUE;
    private boolean storedSacks;
    private boolean storedStorage;
    private Map<String, Long> stored = Map.of();

    /** Every count the settings select, by SkyBlock id. A fresh map the caller may keep. */
    Map<String, Long> collect(SBSConfig.CraftHelperSettings cfg) {
        Map<String, Long> out = new HashMap<>(stored(cfg));
        if (cfg.countInventory) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player != null) {
                Inventory inventory = minecraft.player.getInventory();
                int slots = Math.min(INVENTORY_SLOTS, inventory.getContainerSize());
                for (int i = 0; i < slots; i++) {
                    ItemStack stack = inventory.getItem(i);
                    add(out, stack, stack == null ? 0 : stack.getCount());
                }
            }
        }
        return out;
    }

    void invalidate() {
        storedRevision = Integer.MIN_VALUE;
    }

    private Map<String, Long> stored(SBSConfig.CraftHelperSettings cfg) {
        if (!cfg.countSacks && !cfg.countStorage) {
            return Map.of();
        }
        StorageIndex index = StorageIndex.getInstance();
        int revision = index.revision();
        if (revision == storedRevision && storedSacks == cfg.countSacks && storedStorage == cfg.countStorage) {
            return stored;
        }
        Map<String, Long> out = new HashMap<>();
        if (cfg.countSacks) {
            for (StorageIndex.Snapshot snapshot : index.snapshotsOf(StorageSource.Kind.SACKS)) {
                for (ItemStack stack : snapshot.items()) {
                    long amount = SackContents.storedAmount(stack);
                    add(out, stack, amount == SackContents.UNKNOWN ? (stack == null ? 0 : stack.getCount()) : amount);
                }
            }
        }
        if (cfg.countStorage) {
            for (StorageIndex.Snapshot snapshot : index.snapshotsOf(STORAGE_KINDS)) {
                addAll(out, snapshot.items());
            }
        }
        stored = out;
        storedRevision = revision;
        storedSacks = cfg.countSacks;
        storedStorage = cfg.countStorage;
        return stored;
    }

    private static void addAll(Map<String, Long> out, List<ItemStack> items) {
        for (ItemStack stack : items) {
            add(out, stack, stack == null ? 0 : stack.getCount());
        }
    }

    private static void add(Map<String, Long> out, ItemStack stack, long amount) {
        if (stack == null || stack.isEmpty() || amount <= 0) {
            return;
        }
        String id = ItemAnalysis.of(stack).id();
        if (id == null || id.isEmpty()) {
            return;
        }
        out.merge(id, amount, Long::sum);
    }
}
