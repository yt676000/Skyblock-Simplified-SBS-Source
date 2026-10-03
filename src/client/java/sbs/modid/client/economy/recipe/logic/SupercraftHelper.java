/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.recipe.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import sbs.modid.SkyblockSimplifiedSBS;

import java.util.Locale;

/**
 * The Supercraft bridge – the SBS "+" auto-craft button, implemented over
 * Hypixel's own command / menu structure.
 *
 * <p>Flow (one user-initiated action, mirroring what the player would do by hand):
 * <ol>
 *   <li>{@link #request} sends Hypixel's {@code /viewrecipe <SKYBLOCK_ID>} command, which opens the
 *       server-side recipe menu for that item.</li>
 *   <li>The tick hook (driven by {@code GuiTrackingMixin}, like the other menu scanners) watches for
 *       that menu to open, finds the "Supercraft" button slot by its item name, and clicks it once
 *       via the vanilla container-input path ({@code MultiPlayerGameMode#handleContainerInput}).</li>
 * </ol>
 *
 * <p>The pending request times out after a few seconds and never fires more than one click, so the
 * mod only ever performs the exact two steps the player asked for. Note: this automates a single
 * server-menu click on Hypixel – standard QoL-mod territory, but as with any such feature, using it
 * on Hypixel is at the player's own discretion.
 */
public final class SupercraftHelper {

    private static final SupercraftHelper INSTANCE = new SupercraftHelper();

    /** How long the helper waits for the recipe menu before giving up. */
    private static final long TIMEOUT_MS = 8_000L;

    private static final String SECTION_SIGN = String.valueOf((char) 0x00A7);

    private volatile boolean pending;
    private volatile long pendingUntil;

    private SupercraftHelper() {
    }

    public static SupercraftHelper getInstance() {
        return INSTANCE;
    }

    /**
     * Starts a Supercraft for the given SkyBlock item: opens the Hypixel recipe menu via command and
     * arms the one-shot Supercraft click. Call from a direct user action (the Supercraft button).
     */
    public void request(String skyblockId) {
        Minecraft minecraft = Minecraft.getInstance();
        if (skyblockId == null || skyblockId.isEmpty() || minecraft.player == null) {
            return;
        }
        minecraft.player.connection.sendCommand("viewrecipe " + skyblockId);
        pending = true;
        pendingUntil = System.currentTimeMillis() + TIMEOUT_MS;
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Supercraft] Requested recipe menu for {}.", skyblockId);
    }

    /** Watches for the recipe menu while a request is pending; clicks Supercraft exactly once. */
    public void tick(Minecraft minecraft) {
        if (!pending || minecraft == null) {
            return;
        }
        if (System.currentTimeMillis() > pendingUntil) {
            pending = false; // menu never appeared – give up quietly
            return;
        }
        Screen screen = sbs.modid.client.core.api.GuiStateManager.getInstance().getCurrentScreen();
        if (!(screen instanceof AbstractContainerScreen<?> container)
                || minecraft.gameMode == null || minecraft.player == null) {
            return;
        }
        AbstractContainerMenu menu = container.getMenu();
        int slotCount = menu.getItems().size();
        for (int slot = 0; slot < Math.max(0, slotCount - 36); slot++) {
            ItemStack stack = menu.getSlot(slot).getItem();
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            String name = stack.getHoverName().getString()
                    .replaceAll(SECTION_SIGN + ".", "").trim().toLowerCase(Locale.ROOT);
            if (name.contains("supercraft")) {
                pending = false;
                minecraft.gameMode.handleContainerInput(menu.containerId, slot, 0,
                        ContainerInput.PICKUP, minecraft.player);
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Supercraft] Clicked Supercraft (slot {}).", slot);
                return;
            }
        }
    }
}
