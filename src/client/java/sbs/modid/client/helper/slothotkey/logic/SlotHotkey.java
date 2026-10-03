/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.slothotkey.logic;

import sbs.modid.client.core.keybind.CommandKeybind;
import sbs.modid.client.core.keybind.Keys;

/**
 * One Slot Hotkey: a key (or mouse button) + optional modifier that, while a specific menu is open,
 * instantly clicks a saved slot – so e.g. "Shift + L in the Pets menu selects the pet in slot 31".
 *
 * <p>A small Gson POJO persisted in {@link sbs.modid.client.core.config.SBSConfig}. The trigger is stored
 * exactly like the rest of the SBS keybind system: {@link #keyCode} is a {@link Keys} bind code – a
 * GLFW key, one of the eight mouse buttons or a wheel direction ({@code 0} = unbound) – and
 * {@link #modifiers} is the same {@code SHIFT|CTRL|ALT} bitmask as {@link CommandKeybind}. The slot is
 * scoped to {@link #menuKey} (the normalized menu title captured at scan time) so it can never fire in
 * the wrong menu.
 */
public final class SlotHotkey {

    private static final int MOD_MASK =
            CommandKeybind.MOD_SHIFT | CommandKeybind.MOD_CTRL | CommandKeybind.MOD_ALT;

    /** User-chosen name shown in the list; falls back to the menu + slot when empty. */
    private String name = "";

    /** The trigger as a {@link Keys} bind code (key, mouse button or wheel); {@code 0} = unbound. */
    private int keyCode = 0;

    /** Required modifiers ({@code SHIFT|CTRL|ALT} bitmask, matching {@link CommandKeybind}); 0 = none. */
    private int modifiers = 0;

    /** The menu slot index this hotkey clicks ({@code Slot.index}); {@code -1} = none saved. */
    private int slot = -1;

    /** Normalized menu title (colours + "(1/3)" page counter stripped, lower-case) the slot belongs to. */
    private String menuKey = "";

    /** Pretty menu title, kept for display in the list. */
    private String menuName = "";

    public SlotHotkey() {
    }

    public String name() {
        return name == null ? "" : name;
    }

    public void setName(String name) {
        this.name = name == null ? "" : name;
    }

    public int keyCode() {
        return keyCode;
    }

    public void setKeyCode(int keyCode) {
        this.keyCode = keyCode;
    }

    public int modifiers() {
        return modifiers & MOD_MASK;
    }

    public void setModifiers(int modifiers) {
        this.modifiers = modifiers & MOD_MASK;
    }

    public int slot() {
        return slot;
    }

    public void setSlot(int slot) {
        this.slot = slot;
    }

    public String menuKey() {
        return menuKey == null ? "" : menuKey;
    }

    public void setMenuKey(String menuKey) {
        this.menuKey = menuKey == null ? "" : menuKey;
    }

    public String menuName() {
        return menuName == null ? "" : menuName;
    }

    public void setMenuName(String menuName) {
        this.menuName = menuName == null ? "" : menuName;
    }

    /** Whether a trigger key/button has been assigned. */
    public boolean isBound() {
        return keyCode != 0;
    }

    /** The trigger as text, e.g. "Shift + L" or "Mouse Right" or "None". */
    public String comboName() {
        if (!isBound()) {
            return "None";
        }
        return modifierPrefix(modifiers()) + Keys.displayName(keyCode).getString();
    }

    /** The name shown in the list – the user name, else "<menu> slot N". */
    public String displayLabel() {
        if (!name().isEmpty()) {
            return name();
        }
        String menu = menuName().isEmpty() ? "Menu" : menuName();
        return menu + " slot " + slot;
    }

    /** Human-readable modifier prefix ("Shift + ", "Ctrl + Alt + ", or "" for none). */
    public static String modifierPrefix(int modifiers) {
        StringBuilder sb = new StringBuilder();
        if ((modifiers & CommandKeybind.MOD_CTRL) != 0) {
            sb.append("Ctrl + ");
        }
        if ((modifiers & CommandKeybind.MOD_SHIFT) != 0) {
            sb.append("Shift + ");
        }
        if ((modifiers & CommandKeybind.MOD_ALT) != 0) {
            sb.append("Alt + ");
        }
        return sb.toString();
    }
}
