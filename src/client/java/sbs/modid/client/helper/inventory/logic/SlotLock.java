/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.inventory.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * The Inventory Slot Lock feature: the single place that knows what a lock <b>is</b> and which
 * actions it forbids. The mixins around it are deliberately thin – each one asks a question here and
 * cancels, so the vanilla inventory logic is never reimplemented, only vetoed.
 *
 * <p><b>Model.</b> A lock is a <i>player-inventory index</i> (0-8 hotbar, 9-35 main, 36-39 armor,
 * 40 offhand), not an item. The lock therefore belongs to the slot, applies in every screen that
 * shows the player inventory, and – being stored in the config – survives closing the inventory and
 * restarting the world. Any number of slots can be locked at once.
 *
 * <p><b>Enforcement.</b> Every container interaction the client can make funnels through
 * {@code MultiPlayerGameMode.handleContainerInput}, and every in-world drop through
 * {@code LocalPlayer.drop}. Vetoing those two choke points covers moving, swapping, stacking,
 * dropping and replacing, and means no packet is ever sent – so the client and server never
 * disagree about what is where.
 *
 * <p><b>Hotbar.</b> Selecting and using a locked hotbar item is not an inventory interaction at all,
 * so it is untouched: the item works exactly as normal, it just cannot leave its slot.
 *
 * <p><b>Known limit.</b> Placement chosen by the <i>server</i> (e.g. a shift-click from a chest that
 * the server decides to route into an empty locked slot) cannot be vetoed from here, because the
 * client never names the destination. Locks on slots that hold an item – the case this feature is
 * for – are fully enforced.
 *
 * <p><b>Bindings are the one exception.</b> A swap between two slots the player linked with
 * {@link SlotBindings} is allowed on a locked slot - the item does not leave the inventory, it moves
 * to the other end of a pair the player chose. Note the consequence: if that binding's hotbar anchor
 * is not itself locked, the item lands somewhere {@link #blocksSelectedDrop} does not guard.
 *
 * <p>Extending: add a new rule to {@link #blocks} (it already receives the full action), or a new
 * lock source by writing to {@code SBSConfig.SlotLockSettings#lockedSlots}.
 */
public final class SlotLock {

    /** Minimum gap between two refusal cues, so holding a click cannot spam sound or chat. */
    private static final long FEEDBACK_COOLDOWN_MS = 400L;

    private static long lastFeedback;

    private SlotLock() {
    }

    private static SBSConfig.SlotLockSettings cfg() {
        return ConfigManager.getInstance().get().slotLock;
    }

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    /** Whether the feature is on at all. Every hook checks this first and then does nothing. */
    public static boolean enabled() {
        return cfg().enabled;
    }

    /** Whether the given player-inventory index is locked. */
    public static boolean isLocked(int inventorySlot) {
        SBSConfig.SlotLockSettings cfg = cfg();
        return cfg.enabled && inventorySlot >= 0 && cfg.lockedSlots.contains(inventorySlot);
    }

    /**
     * The player-inventory index behind a menu slot, or {@code -1} when the slot belongs to some
     * other container (a chest, a crafting grid, ...) and can never be locked.
     */
    public static int inventoryIndexOf(Slot slot) {
        return slot != null && slot.container instanceof Inventory ? slot.index : -1;
    }

    /** Whether a menu slot maps to a locked player-inventory index. */
    public static boolean isLocked(Slot slot) {
        return isLocked(inventoryIndexOf(slot));
    }

    /** Locks an unlocked slot, unlocks a locked one, and persists the change immediately. */
    public static void toggle(int inventorySlot) {
        if (inventorySlot < 0) {
            return;
        }
        SBSConfig.SlotLockSettings cfg = cfg();
        boolean locked = !cfg.lockedSlots.remove(inventorySlot);
        if (locked) {
            cfg.lockedSlots.add(inventorySlot);
        }
        ConfigManager.getInstance().save();
        // Toggling always confirms itself: the deny cues below are a separate, optional thing.
        playSound(locked ? 1.4f : 0.9f);
        overlay(locked ? "§bSlot locked" : "§7Slot unlocked");
    }

    /** Whether the configured lock modifier key (or mouse button) is currently held. */
    public static boolean lockKeyDown() {
        return sbs.modid.client.core.keybind.Keys.isDown(cfg().lockKey);
    }

    /** Whether the swap-bypass key is held (lets a locked item be moved, not dropped or sold). */
    public static boolean swapKeyDown() {
        return sbs.modid.client.core.keybind.Keys.isDown(cfg().swapKey);
    }

    // ------------------------------------------------------------------
    // Rules
    // ------------------------------------------------------------------

    /**
     * Whether a container interaction must be refused.
     *
     * @param player  the acting player (its {@code containerMenu} resolves the slot ids)
     * @param slotId  the clicked menu slot, or a negative sentinel (e.g. -999 = click outside)
     * @param button  action-specific: for {@link ContainerInput#SWAP} the target hotbar index
     * @param input   what the click is trying to do
     */
    public static boolean blocks(Player player, int slotId, int button, ContainerInput input) {
        SBSConfig.SlotLockSettings cfg = cfg();
        if (!cfg.enabled || cfg.lockedSlots.isEmpty() || player == null) {
            return false;
        }
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == null) {
            return false;
        }
        // A Slot Bindings swap is let through, locked or not. A lock is there so an item is not
        // dropped, sold or mis-dragged away, and this exchange loses nothing - it moves the item
        // into the other slot of a pair the player deliberately linked. The same concession the
        // swap key already makes for PICKUP, and without it locked gear cannot be linked at all,
        // because that bypass does not cover SWAP.
        //
        // Identified by the pair rather than by a flag SlotBindings sets around its own call: the
        // test is true of the pair however the input arrived, so a number key that performs the
        // identical exchange behaves identically, and there is no flag that can leak.
        if (input == ContainerInput.SWAP && slotId >= 0 && slotId < menu.slots.size()
                && SlotBindings.linkedSwap(menu, SlotBindings.inventoryIndexOf(menu.getSlot(slotId)), button)) {
            return false;
        }
        // A number-key swap never touches the destination slot's own id, so it has to be checked
        // explicitly - otherwise "1" would happily swap an item out of a locked hotbar slot.
        if (input == ContainerInput.SWAP && isLocked(button)) {
            return true;
        }
        // Double-click-collect sweeps matching slots rather than the clicked one, so it is refused
        // when - and only when - the sweep would really take from a locked slot. Merely holding the
        // same item is not enough: vanilla drains partial stacks first and stops once the cursor is
        // full, and refusing on sight broke gathering for anyone who locked a stack of a common item.
        if (input == ContainerInput.PICKUP_ALL) {
            return wouldSweepLocked(player, menu, slotId, button);
        }
        if (slotId < 0 || slotId >= menu.slots.size()) {
            return false;
        }
        if (!isLocked(menu.getSlot(slotId))) {
            return false;
        }
        // Swap bypass: while the swap key is held, a plain PICKUP (pick the item up / place it back)
        // is allowed so a locked set can be reorganised. Dropping (THROW) and shift-selling
        // (QUICK_MOVE) are NOT PICKUP, so they stay blocked - exactly the user's ask.
        if (swapKeyDown() && input == ContainerInput.PICKUP) {
            return false;
        }
        // Everything else (pickup without the key, shift-click, throw, clone, drag) on a locked slot:
        // its item may neither leave nor be replaced by the carried one.
        return true;
    }

    /**
     * Whether a collect-all would take anything out of a locked slot. The gate and the per-slot test
     * are vanilla's own ({@code AbstractContainerMenu.doClick}); {@link PickAllSweep} replays the
     * order, which is what decides whether the locked slot is ever reached.
     */
    private static boolean wouldSweepLocked(Player player, AbstractContainerMenu menu, int slotId, int button) {
        ItemStack carried = menu.getCarried();
        if (carried.isEmpty() || slotId < 0 || slotId >= menu.slots.size()) {
            return false;
        }
        Slot clicked = menu.getSlot(slotId);
        if (clicked.hasItem() && clicked.mayPickup(player)) {
            return false;   // vanilla sweeps nothing in this case, so there is nothing to refuse
        }
        List<PickAllSweep.Candidate> candidates = new ArrayList<>(menu.slots.size());
        boolean lockedMatch = false;
        for (Slot slot : menu.slots) {
            ItemStack stack = slot.getItem();
            boolean eligible = slot.hasItem()
                    && AbstractContainerMenu.canItemQuickReplace(slot, carried, true)
                    && slot.mayPickup(player)
                    && menu.canTakeItemForPickAll(carried, slot);
            lockedMatch |= eligible && isLocked(slot);
            candidates.add(new PickAllSweep.Candidate(eligible, stack.getCount(), stack.getMaxStackSize()));
        }
        if (!lockedMatch) {
            return false;
        }
        for (int index : PickAllSweep.drained(carried.getCount(), carried.getMaxStackSize(), button, candidates)) {
            if (isLocked(menu.getSlot(index))) {
                return true;
            }
        }
        return false;
    }

    /** Whether dropping the currently selected hotbar item must be refused. */
    public static boolean blocksSelectedDrop(Player player) {
        if (!enabled() || !cfg().blockHotbarDrop || player == null) {
            return false;
        }
        Inventory inventory = player.getInventory();
        return isLocked(inventory.getSelectedSlot()) && !inventory.getSelectedItem().isEmpty();
    }

    // ------------------------------------------------------------------
    // Feedback
    // ------------------------------------------------------------------

    /** The optional cue for a refused action, rate-limited so a held click cannot spam it. */
    public static void denied() {
        long now = System.currentTimeMillis();
        if (now - lastFeedback < FEEDBACK_COOLDOWN_MS) {
            return;
        }
        lastFeedback = now;
        SBSConfig.SlotLockSettings cfg = cfg();
        if (cfg.denySound) {
            playSound(0.7f);
        }
        if (cfg.denyMessage) {
            overlay("§cThis slot is locked");
        }
    }

    private static void playSound(float pitch) {
        Player player = Minecraft.getInstance().player;
        if (player != null) {
            player.playSound(SoundEvents.NOTE_BLOCK_BASS.value(), 0.5f, pitch);
        }
    }

    private static void overlay(String text) {
        Player player = Minecraft.getInstance().player;
        if (player != null) {
            player.sendOverlayMessage(Component.literal(text));
        }
    }
}
