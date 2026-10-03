/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.slothotkey.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import sbs.modid.client.social.chat.logic.SBSChat;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig.SlotHotkeysSettings;
import sbs.modid.client.core.keybind.CommandKeybind;
import sbs.modid.client.core.keybind.Keys;
import sbs.modid.client.core.keybind.WheelNotches;
import sbs.modid.client.core.mixin.AbstractContainerScreenAccessor;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Runtime brain of the Slot Hotkeys module: the two-step "scan a slot, then bind a hotkey to it"
 * flow and the hotkey firing.
 *
 * <p><b>Scan:</b> pressing the module's Scan key while a menu is open arms scan mode; the next slot
 * click is swallowed (never sent to the server, so you don't pick the item up) and its slot index +
 * menu are remembered as the "pending" slot. <b>Edit Hotkeys → Add</b> then turns that pending slot
 * into a {@link SlotHotkey} you give a key/modifier/name.
 *
 * <p><b>Fire:</b> while the matching menu is open, pressing a hotkey's key+modifier instantly clicks
 * its slot via {@code MultiPlayerGameMode.handleContainerInput} – the exact packet a real left-click
 * sends. Everything runs on the client thread from the container screen's input, so no locking.
 */
public final class SlotHotkeyManager {

    private static final SlotHotkeyManager INSTANCE = new SlotHotkeyManager();

    private static final int MOD_MASK =
            CommandKeybind.MOD_SHIFT | CommandKeybind.MOD_CTRL | CommandKeybind.MOD_ALT;

    /** Strips a "(1/3)" style page counter so a hotkey survives the menu changing page. */
    private static final Pattern PAGE_COUNTER = Pattern.compile("\\(\\s*\\d+\\s*/\\s*\\d+\\s*\\)");
    private static final char SECTION_SIGN = (char) 0x00A7;

    /** Whole turns of the wheel, so a fractional-scroll device does not fire a hotkey ten times. */
    private final WheelNotches wheel = new WheelNotches();

    private boolean armed;
    private int pendingSlot = -1;
    private String pendingMenuKey = "";
    private String pendingMenuName = "";

    private SlotHotkeyManager() {
    }

    public static SlotHotkeyManager getInstance() {
        return INSTANCE;
    }

    private static SlotHotkeysSettings cfg() {
        return ConfigManager.getInstance().get().slotHotkeys;
    }

    public void save() {
        ConfigManager.getInstance().save();
    }

    // ------------------------------------------------------------------ input entry points (mixin)

    /**
     * Keyboard press inside a container. Returns whether it was consumed (scan armed, or a hotkey
     * fired) so the mixin can cancel the vanilla handling.
     */
    public boolean handleKey(AbstractContainerScreen<?> screen, int code, int modifiers) {
        SlotHotkeysSettings settings = cfg();
        if (!settings.enabled || code == 0) {
            return false;
        }
        if (settings.scanKey != 0 && code == settings.scanKey) {
            arm();
            return true;
        }
        return tryFire(screen, code, modifiers);
    }

    /**
     * Mouse press inside a container. When scan is armed the click is captured as the pending slot;
     * otherwise a mouse-button hotkey (or the mouse-bound scan key) may fire.
     */
    public boolean handleMouse(AbstractContainerScreen<?> screen, int button, int modifiers,
                               double mouseX, double mouseY) {
        SlotHotkeysSettings settings = cfg();
        if (!settings.enabled) {
            return false;
        }
        if (armed) {
            captureSlot(screen, mouseX, mouseY);
            return true;
        }
        int code = Keys.ofMouseButton(button);
        if (settings.scanKey != 0 && settings.scanKey == code) {
            arm();
            return true;
        }
        return tryFire(screen, code, modifiers);
    }

    /**
     * Wheel notch inside a container. The editor accepts a wheel bind like any other, so the wheel
     * has to reach the same firing path – otherwise the hotkey is stored and silently never fires.
     *
     * <p>Only claimed when a hotkey actually matches: a menu that scrolls its own list keeps its
     * wheel, and a scan waiting for a click is not interrupted by one.
     */
    public boolean handleScroll(AbstractContainerScreen<?> screen, double scrollY, int modifiers) {
        SlotHotkeysSettings settings = cfg();
        if (!settings.enabled || armed || scrollY == 0) {
            return false;
        }
        return tryFire(screen, wheel.step(scrollY), modifiers);
    }

    // ------------------------------------------------------------------ scan

    private void arm() {
        armed = true;
        SBSChat.send("Slot scan armed — click any slot to save it for the next Hotkey.");
    }

    /** True while waiting for the scan click (used by the screen/UI copy). */
    public boolean isArmed() {
        return armed;
    }

    private void captureSlot(AbstractContainerScreen<?> screen, double mouseX, double mouseY) {
        armed = false;
        int slotIndex = -1;
        // The Loadouts overlay covers the whole screen while the vanilla menu underneath stays
        // small, so the vanilla hovered-slot lookup points at nothing there. Ask the overlay for the
        // real menu slot behind the card the player actually clicked instead.
        var loadouts = sbs.modid.client.helper.loadouts.LoadoutsOverlay.getInstance();
        if (loadouts.isActive(screen)) {
            slotIndex = loadouts.menuSlotAt(screen, mouseX, mouseY);
            if (slotIndex < 0) {
                SBSChat.send("No loadout card under the cursor (its page must be the open one) — "
                        + "arm the scan again and click directly on a card.");
                return;
            }
        } else {
            Slot slot = ((AbstractContainerScreenAccessor) screen).skyblockSimplified$hoveredSlot();
            if (slot == null) {
                SBSChat.send("No slot under the cursor — arm the scan again and click directly on a slot.");
                return;
            }
            slotIndex = slot.index;
        }
        pendingSlot = slotIndex;
        String title = screen.getTitle() != null ? screen.getTitle().getString() : "";
        pendingMenuName = strip(title).trim();
        pendingMenuKey = normalize(title);
        save(); // nothing persisted here, but keeps behaviour uniform if pending is added later
        SBSChat.send("Saved slot " + pendingSlot + " in \"" + pendingMenuName
                + "\". Open the module → Edit Hotkeys → Add to bind it.");
    }

    public boolean hasPending() {
        return pendingSlot >= 0;
    }

    public int pendingSlot() {
        return pendingSlot;
    }

    public String pendingMenuName() {
        return pendingMenuName;
    }

    // ------------------------------------------------------------------ hotkey CRUD

    /**
     * Creates a hotkey from the currently pending slot (unbound key, empty name) and returns it, or
     * {@code null} when nothing has been scanned yet.
     */
    public SlotHotkey addFromPending() {
        if (!hasPending()) {
            return null;
        }
        SlotHotkey hotkey = new SlotHotkey();
        hotkey.setSlot(pendingSlot);
        hotkey.setMenuKey(pendingMenuKey);
        hotkey.setMenuName(pendingMenuName);
        cfg().hotkeys.add(hotkey);
        save();
        return hotkey;
    }

    public List<SlotHotkey> hotkeys() {
        return cfg().hotkeys;
    }

    public void remove(SlotHotkey hotkey) {
        cfg().hotkeys.remove(hotkey);
        save();
    }

    // ------------------------------------------------------------------ firing

    private boolean tryFire(AbstractContainerScreen<?> screen, int code, int modifiers) {
        if (code == 0) {
            return false;   // nothing bindable was pressed; an unbound hotkey stores 0 and must not match
        }
        String menuKey = normalize(screen.getTitle() != null ? screen.getTitle().getString() : "");
        int mods = modifiers & MOD_MASK;
        for (SlotHotkey hotkey : cfg().hotkeys) {
            if (hotkey.keyCode() == code && (hotkey.modifiers() & MOD_MASK) == mods
                    && hotkey.slot() >= 0 && hotkey.menuKey().equalsIgnoreCase(menuKey)) {
                execute(screen, hotkey);
                return true;
            }
        }
        return false;
    }

    private void execute(AbstractContainerScreen<?> screen, SlotHotkey hotkey) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode == null || mc.player == null) {
            return;
        }
        AbstractContainerMenu menu = screen.getMenu();
        if (hotkey.slot() < 0 || hotkey.slot() >= menu.slots.size()) {
            SBSChat.send("Hotkey slot " + hotkey.slot() + " is not in this menu.");
            return;
        }
        // A plain left-click (PICKUP, button 0) is exactly what clicking the slot does; Hypixel's
        // server menus turn that into the button action (select pet, buy, ...).
        mc.gameMode.handleContainerInput(menu.containerId, hotkey.slot(), 0,
                ContainerInput.PICKUP, mc.player);
    }

    // ------------------------------------------------------------------ helpers

    /** Menu-match key: colours + "(1/3)" page counter stripped, whitespace-collapsed, lower-case. */
    private static String normalize(String title) {
        String core = PAGE_COUNTER.matcher(strip(title)).replaceAll(" ");
        return core.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    /** Removes {@code §x} colour / format codes. */
    private static String strip(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == SECTION_SIGN && i + 1 < text.length()) {
                i++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
