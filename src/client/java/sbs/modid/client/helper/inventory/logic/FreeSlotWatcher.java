/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.inventory.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import sbs.modid.client.core.alert.Alerts;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.audio.SbsAudio;
import sbs.modid.client.core.location.SkyBlockLocation;

/**
 * Watches how much room is left in the player's main inventory and raises the warning.
 *
 * <p><b>What counts.</b> Slots 0-35 - the nine hotbar slots and the twenty-seven above them.
 * Armor (36-39) and the offhand (40) are deliberately not looked at: they are not places loot
 * lands, and counting them would make "full" arrive four items early. The layout is the one
 * {@code helper/storage/StorageIndex} and {@code helper/quest/logic/QuestTracker} already read.
 *
 * <p><b>The SkyBlock Menu slot needs no special case</b>, and that is the point. It holds an item,
 * so an empty-stack count never counts it as free - which makes the ceiling 35 rather than 36 on a
 * profile that has it. Nothing here hardcodes a hotbar index, because a slot number is exactly the
 * kind of fact that is right until Hypixel moves it.
 *
 * <p><b>Read-only.</b> It counts and it tells the player. It never moves, drops or uses anything.
 *
 * @see FullInventoryState for when the warning is allowed to fire
 */
public final class FreeSlotWatcher {

    /** Main inventory slots: hotbar plus the three rows above it. */
    public static final int MAIN_SLOTS = 36;

    /**
     * How often the inventory is counted, in ms. ESTIMATED - chosen inside the 250-500ms band the
     * feature was specified with. Nobody has measured whether a fast pickup burst can cross the
     * threshold and recover inside one window, which would swallow a warning.
     */
    private static final long SCAN_INTERVAL_MS = 300L;

    private static final FreeSlotWatcher INSTANCE = new FreeSlotWatcher();

    private final FullInventoryState state = new FullInventoryState();

    /** Last count taken, or -1 while nothing has been read. Drives the HUD without re-counting. */
    private int freeSlots = -1;
    private long lastScanMs;

    private FreeSlotWatcher() {
    }

    public static FreeSlotWatcher getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.FullInventorySettings cfg() {
        return ConfigManager.getInstance().get().fullInventory;
    }

    /** Free slots as of the last scan, or -1 when there has not been one. */
    public int freeSlots() {
        return freeSlots;
    }

    /** Whether the last reading was at or below the player's threshold. */
    public boolean low() {
        return freeSlots >= 0 && freeSlots <= cfg().threshold;
    }

    /**
     * Called once per client tick from {@code core/mixin/GuiTrackingMixin}. Does nothing at all
     * beyond a config read while the module is off, which is the state it is in for most players.
     */
    public void tick(Minecraft minecraft) {
        SBSConfig.FullInventorySettings cfg = cfg();
        if (!cfg.enabled) {
            forget();
            return;
        }
        if (minecraft == null || minecraft.player == null) {
            forget();
            return;
        }
        // No sidebar means no SkyBlock. Failing closed: the harmless direction for a warning is
        // not to arrive, not to arrive somewhere it makes no sense.
        if (SkyBlockLocation.zone().isEmpty()) {
            forget();
            return;
        }
        if (cfg.offInDungeons && SkyBlockLocation.inDungeon()) {
            forget();
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastScanMs < SCAN_INTERVAL_MS) {
            return;
        }
        lastScanMs = now;

        freeSlots = countFree(minecraft.player.getInventory());
        if (state.update(freeSlots, cfg.threshold, cfg.cooldownSeconds * 1000L, now)) {
            fire(freeSlots, cfg);
        }
    }

    /** Empty stacks among the main inventory slots. */
    private static int countFree(Inventory inventory) {
        int free = 0;
        for (int slot = 0; slot < MAIN_SLOTS && slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                free++;
            }
        }
        return free;
    }

    private static void fire(int free, SBSConfig.FullInventorySettings cfg) {
        String title = free == 0 ? "Inventory full" : "Inventory nearly full";
        String detail = free == 0
                ? "No free slots left"
                : free + (free == 1 ? " free slot left" : " free slots left");
        Alerts.send(new Alerts.Alert(title, detail, SbsAudio.Tone.ALARM, null), cfg.notifyChannels);
    }

    /**
     * Drops the reading and re-arms, without firing anything. Used whenever the feature stops
     * applying - switched off, no player, off SkyBlock, in a dungeon - so walking back into a
     * situation it applies to is a fresh start rather than a continuation of a stale one.
     */
    private void forget() {
        freeSlots = -1;
        state.reset();
    }

    /** World change, server hop: the inventory on the other side is a new reading. */
    public void onWorldChange() {
        forget();
        lastScanMs = 0L;
    }
}
