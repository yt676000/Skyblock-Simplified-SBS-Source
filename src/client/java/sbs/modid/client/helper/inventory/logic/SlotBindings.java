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
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.config.SBSConfig.SlotBinding;

import java.util.ArrayList;
import java.util.List;

/**
 * The Slot Bindings feature: tie inventory slots to a hotbar slot, then shift-left-click either one
 * to exchange their items in place.
 *
 * <p><b>Model.</b> A binding is a hotbar slot (0-8) or the offhand (40) — the <i>anchor</i> — plus
 * the slots that swap into it. Every index here is a <b>player-inventory container index</b>
 * (0-8 hotbar, 9-35 main, 36-39 armour, 40 offhand), read with {@link Slot#getContainerSlot()}, so a
 * binding means the same physical slot in the inventory screen and in a chest. A slot belongs to at
 * most one binding: binding it again moves it.
 *
 * <p><b>Why an anchor.</b> The whole swap has to be one action. Vanilla's {@code SWAP} exchanges any
 * slot with a hotbar or offhand slot in a single container input — it is exactly what pressing 1-9
 * while hovering a slot does — and nothing in the protocol exchanges two arbitrary slots. Doing that
 * would mean three clicks synthesized on the player's behalf, which the mod does not do (see
 * {@code AGENTS.md}). So a binding always has one hotbar side, and one shift-click is one packet.
 *
 * <p><b>Which partner.</b> Clicking a member swaps it with the anchor. Clicking the anchor swaps it
 * with the first member that is <i>empty</i>, falling back to the first member — so sending an item
 * back puts it in the hole it came from, with no memory of the last swap to go stale.
 *
 * <p><b>A lock does not refuse it.</b> A binding swap between two slots the player linked is let
 * through even when either end is locked - see {@link SlotLock#blocks}. A lock is there so an item is
 * not dropped, sold or mis-dragged away, and this exchange loses nothing: it moves the item into the
 * other slot of a pair the player set up themselves. Everything else a lock forbids is untouched.
 * (This reverses the original decision, where a lock vetoed the swap and there was no way round it:
 * the swap key's bypass only covers {@code PICKUP}, so locked gear could not be linked at all.)
 */
public final class SlotBindings {

    /** Offhand container index; the one non-hotbar slot vanilla's SWAP accepts. */
    public static final int OFFHAND = Inventory.SLOT_OFFHAND;

    /** The slot waiting for its partner, or {@code -1}. Session state — a half-made binding is not saved. */
    private static int pending = -1;

    /** The menu {@link #pending} was clicked in; a pending pick does not survive closing that screen. */
    private static AbstractContainerMenu pendingMenu;

    private SlotBindings() {
    }

    private static SBSConfig.SlotBindingsSettings cfg() {
        return ConfigManager.getInstance().get().slotBindings;
    }

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    /** Whether the feature is on at all. Every hook asks this first and then does nothing. */
    public static boolean enabled() {
        return cfg().enabled;
    }

    /** Whether the bind key (or mouse button) is currently held. */
    public static boolean bindKeyDown() {
        return sbs.modid.client.core.keybind.Keys.isDown(cfg().bindKey);
    }

    /**
     * The player-inventory index behind a menu slot, or {@code -1} when the slot belongs to some
     * other container (a chest, a Hypixel menu, a crafting grid) and can never be bound.
     *
     * <p>{@link Slot#getContainerSlot()}, not {@code Slot.index}: the latter is the slot's position
     * in the <i>menu</i>, which is a different number for the same physical slot in every screen.
     */
    public static int inventoryIndexOf(Slot slot) {
        return slot != null && slot.container instanceof Inventory ? slot.getContainerSlot() : -1;
    }

    /** Whether an index is one a binding can be anchored to. */
    public static boolean isAnchorSlot(int inventoryIndex) {
        return (inventoryIndex >= 0 && inventoryIndex < Inventory.SELECTION_SIZE)
                || inventoryIndex == OFFHAND;
    }

    /** The live list of bindings; never null. */
    private static List<SlotBinding> bindings() {
        SBSConfig.SlotBindingsSettings settings = cfg();
        if (settings.bindings == null) {
            settings.bindings = new ArrayList<>();
        }
        return settings.bindings;
    }

    /** The binding an index belongs to (as anchor or member), or {@code null}. */
    public static SlotBinding bindingOf(int inventoryIndex) {
        if (inventoryIndex < 0) {
            return null;
        }
        for (SlotBinding binding : bindings()) {
            if (binding.anchor == inventoryIndex || members(binding).contains(inventoryIndex)) {
                return binding;
            }
        }
        return null;
    }

    /**
     * Whether a swap between these two player-inventory indices is one the player linked, in a menu
     * where bindings apply at all.
     *
     * <p>Asked by {@link SlotLock} to tell a swap the player set up apart from a stray number-key
     * press over a locked slot. Stateless on purpose: it is true of the pair however the input
     * arrived, so an identical exchange behaves identically whoever sent it, and there is no
     * "this next click is mine" flag that can leak and leave the next swap unguarded.
     *
     * <p>The feature switch and the screen restriction are asked here rather than at the call site,
     * so a lock exception can never outlive the feature that justifies it: bindings switched off, or
     * a chest while they are restricted to the player inventory, and this is no longer a binding
     * swap - it is a number key over a locked slot, which the lock still refuses.
     */
    public static boolean linkedSwap(AbstractContainerMenu menu, int indexA, int indexB) {
        if (!appliesTo(menu) || indexA == indexB) {
            return false;
        }
        SlotBinding binding = bindingOf(indexA);
        if (binding == null) {
            return false;
        }
        if (binding.anchor == indexA) {
            return members(binding).contains(indexB);
        }
        return binding.anchor == indexB && members(binding).contains(indexA);
    }

    /** Whether bindings do anything in this menu at all - the switch plus the screen restriction. */
    private static boolean appliesTo(AbstractContainerMenu menu) {
        return enabled() && menu != null
                && (!cfg().onlyPlayerInventory || menu instanceof InventoryMenu);
    }

    /** A binding's member list; never null (Gson can leave it so on a hand-edited config). */
    public static List<Integer> members(SlotBinding binding) {
        if (binding.members == null) {
            binding.members = new ArrayList<>();
        }
        return binding.members;
    }

    /**
     * The 1-based number shown on a bound slot, or {@code 0} when it is not bound. Position in the
     * list, so the two ends of one binding always wear the same number.
     *
     * <p>Positional on purpose: it is a pairing aid, never an id. Removing a binding renumbers the
     * ones after it, and nothing is stored against the number for that to break.
     */
    public static int groupNumber(int inventoryIndex) {
        List<SlotBinding> list = bindings();
        for (int i = 0; i < list.size(); i++) {
            SlotBinding binding = list.get(i);
            if (binding.anchor == inventoryIndex || members(binding).contains(inventoryIndex)) {
                return i + 1;
            }
        }
        return 0;
    }

    /** Whether an index is the hotbar/offhand end of its binding. */
    public static boolean isAnchor(int inventoryIndex) {
        SlotBinding binding = bindingOf(inventoryIndex);
        return binding != null && binding.anchor == inventoryIndex;
    }

    /** True while a first slot has been picked and the next click completes the binding. */
    public static boolean hasPending() {
        Player player = Minecraft.getInstance().player;
        if (pending >= 0 && player != null && player.containerMenu == pendingMenu) {
            return true;
        }
        clearPending();   // the screen it was started in is gone; so is the half-made binding
        return false;
    }

    /** The slot waiting for a partner, or {@code -1}. */
    public static int pendingSlot() {
        return hasPending() ? pending : -1;
    }

    public static void clearPending() {
        pending = -1;
        pendingMenu = null;
    }

    // ------------------------------------------------------------------
    // Binding
    // ------------------------------------------------------------------

    /**
     * The bind key + left click: picks the first slot, then ties the second to it.
     *
     * <p>Clicking the same slot twice cancels, and clicking a slot that is already bound while
     * nothing is pending starts from it — so extending a binding to a third slot is the same two
     * clicks as making it.
     */
    public static void clicked(int inventoryIndex) {
        if (inventoryIndex < 0) {
            return;
        }
        if (!hasPending()) {
            pending = inventoryIndex;
            pendingMenu = Minecraft.getInstance().player == null
                    ? null : Minecraft.getInstance().player.containerMenu;
            overlay("§bPick the slot to bind this one to");
            playSound(1.2f);
            return;
        }
        int first = pending;
        clearPending();
        if (first == inventoryIndex) {
            overlay("§7Binding cancelled");
            playSound(0.9f);
            return;
        }
        bind(first, inventoryIndex);
    }

    /**
     * Ties two slots together, or explains why it cannot.
     *
     * <p>One of the two has to be a hotbar or offhand slot; that side becomes the anchor. Binding an
     * already-bound member to a different anchor moves it rather than leaving it in two bindings,
     * which would make "the partner" ambiguous.
     */
    public static void bind(int a, int b) {
        boolean anchorA = isAnchorSlot(a);
        boolean anchorB = isAnchorSlot(b);
        if (!anchorA && !anchorB) {
            overlay("§cOne of the two has to be a hotbar or offhand slot");
            playSound(0.7f);
            return;
        }
        if (anchorA && anchorB) {
            // Two hotbar slots is a legal swap, but only one of them can be the hub - the game
            // exchanges a slot WITH the hotbar, never hotbar with hotbar. An existing hub keeps the
            // job so binding a third slot to it never quietly dismantles what is already there; two
            // existing hubs are refused rather than merged, because merging would throw one away.
            boolean hubA = bindingWithAnchor(a) != null;
            boolean hubB = bindingWithAnchor(b) != null;
            if (hubA && hubB) {
                overlay("§c" + name(b) + " already has its own binding - unbind it first");
                playSound(0.7f);
                return;
            }
            anchorA = hubA || !hubB;
        }
        int anchor = anchorA ? a : b;
        int member = anchorA ? b : a;

        unbindSlot(member);   // a slot lives in one binding only
        SlotBinding binding = bindingWithAnchor(anchor);
        if (binding == null) {
            binding = new SlotBinding();
            binding.anchor = anchor;
            bindings().add(binding);
        }
        if (!members(binding).contains(member)) {
            members(binding).add(member);
        }
        save();
        overlay("§aBound §b" + name(member) + " §7↔ §b" + name(anchor));
        playSound(1.4f);
    }

    /**
     * Removes a slot from its binding (and the binding itself once it has no members left).
     *
     * <p>Unbinding the hub takes the whole binding with it, so the message says which happened –
     * "unbound one slot" and "dropped the binding those four slots were in" are very different
     * outcomes to read after the same click.
     */
    public static void unbind(int inventoryIndex) {
        SlotBinding binding = bindingOf(inventoryIndex);
        if (binding == null) {
            return;
        }
        boolean wholeBinding = binding.anchor == inventoryIndex;
        int lost = members(binding).size();
        if (unbindSlot(inventoryIndex)) {
            save();
            overlay(wholeBinding
                    ? "§7Removed the binding on " + name(inventoryIndex) + " (" + lost
                            + (lost == 1 ? " slot" : " slots") + " freed)"
                    : "§7Unbound " + name(inventoryIndex));
            playSound(0.9f);
        }
    }

    /** Drops every binding. The settings page asks twice before calling this. */
    public static void clearAll() {
        bindings().clear();
        save();
    }

    public static int count() {
        return bindings().size();
    }

    /**
     * Removes {@code inventoryIndex} wherever it appears. Unbinding an anchor takes the whole
     * binding with it: what is left would be members with nothing to swap into.
     *
     * @return whether anything changed
     */
    private static boolean unbindSlot(int inventoryIndex) {
        boolean changed = false;
        List<SlotBinding> list = bindings();
        for (SlotBinding binding : new ArrayList<>(list)) {
            if (binding.anchor == inventoryIndex) {
                list.remove(binding);
                changed = true;
            } else if (members(binding).remove(Integer.valueOf(inventoryIndex))) {
                changed = true;
                if (members(binding).isEmpty()) {
                    list.remove(binding);
                }
            }
        }
        return changed;
    }

    private static SlotBinding bindingWithAnchor(int anchor) {
        for (SlotBinding binding : bindings()) {
            if (binding.anchor == anchor) {
                return binding;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Swapping
    // ------------------------------------------------------------------

    /** What one shift-click has to do: the menu slot to click and the hotbar index to swap it with. */
    public record Swap(int menuSlotId, int hotbarIndex) {
    }

    /**
     * Resolves a shift-left-click on {@code clicked} into the single SWAP that performs it, or
     * {@code null} when the click is nothing to do with a binding and vanilla should have it.
     *
     * <p>The packet is always "this menu slot, exchanged with that hotbar index", so the member side
     * is the one named as the slot even when the anchor is what was clicked.
     */
    public static Swap resolve(AbstractContainerMenu menu, Slot clicked) {
        if (!appliesTo(menu)) {
            return null;   // off, or a chest while bindings are restricted to the player inventory
        }
        if (!menu.getCarried().isEmpty()) {
            return null;   // an item on the cursor means the click is a place, not a swap
        }
        int index = inventoryIndexOf(clicked);
        SlotBinding binding = bindingOf(index);
        if (binding == null || members(binding).isEmpty() || !isAnchorSlot(binding.anchor)) {
            return null;
        }
        int member = binding.anchor == index ? partnerFor(menu, binding) : index;
        if (member < 0) {
            return null;
        }
        int menuSlotId = menuSlotOf(menu, member);
        return menuSlotId < 0 ? null : new Swap(menuSlotId, binding.anchor);
    }

    /**
     * Which member the anchor swaps with: the first empty one, else the first bound.
     *
     * <p>Empty first because that is the hole the item on the hotbar came out of — sending it back
     * should not displace a second item to do it. No record of the last swap is kept, so there is
     * nothing that can go stale between two screens.
     */
    private static int partnerFor(AbstractContainerMenu menu, SlotBinding binding) {
        List<Integer> members = members(binding);
        for (int member : members) {
            int menuSlotId = menuSlotOf(menu, member);
            if (menuSlotId >= 0 && !menu.getSlot(menuSlotId).hasItem()) {
                return member;
            }
        }
        return members.isEmpty() ? -1 : members.get(0);
    }

    /** The id of the menu slot showing a player-inventory index, or {@code -1} if this menu has none. */
    private static int menuSlotOf(AbstractContainerMenu menu, int inventoryIndex) {
        for (Slot slot : menu.slots) {
            if (inventoryIndexOf(slot) == inventoryIndex) {
                return slot.index;
            }
        }
        return -1;
    }

    /** The cue for a completed swap. Separate from the bind cues so it can be switched off alone. */
    public static void swapped() {
        if (cfg().swapSound) {
            playSound(1.6f);
        }
    }

    // ------------------------------------------------------------------
    // Feedback
    // ------------------------------------------------------------------

    /**
     * "Hotbar 3" / "Inventory 12" / "Helmet" / "Offhand" — the slot in the words the player would
     * use, never the raw index. The inventory number counts the 27 main slots from 1, which is what
     * someone looking at the screen would count.
     */
    public static String name(int inventoryIndex) {
        if (inventoryIndex == OFFHAND) {
            return "Offhand";
        }
        if (inventoryIndex >= 0 && inventoryIndex < Inventory.SELECTION_SIZE) {
            return "Hotbar " + (inventoryIndex + 1);
        }
        if (inventoryIndex < Inventory.INVENTORY_SIZE) {
            return "Inventory " + (inventoryIndex - Inventory.SELECTION_SIZE + 1);
        }
        return switch (inventoryIndex) {
            case 36 -> "Boots";
            case 37 -> "Leggings";
            case 38 -> "Chestplate";
            case 39 -> "Helmet";
            default -> "Slot " + inventoryIndex;
        };
    }

    private static void save() {
        ConfigManager.getInstance().save();
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
