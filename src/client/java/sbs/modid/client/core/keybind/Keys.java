/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.keybind;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Shared key / mouse handling for the SBS keybind config ints — the one place that decides what a
 * stored bind code means.
 *
 * <p>A bound input is a single {@code int}: a GLFW key code as usual, OR one of the mouse codes
 * below. {@code 0} means unbound. Encoding the mouse into the same int is what lets every keybind
 * row, the command keybinds and the slot hotkeys accept a mouse button without any of them growing
 * a second field.
 *
 * <table>
 *   <caption>The code space</caption>
 *   <tr><td>{@code 0}</td><td>unbound</td></tr>
 *   <tr><td>{@code 1}..{@code 348}</td><td>GLFW key codes, exactly what {@code KeyEvent.key()} returns</td></tr>
 *   <tr><td>{@link #MOUSE_BASE}{@code + 0..7}</td><td>all eight GLFW mouse buttons</td></tr>
 *   <tr><td>{@link #WHEEL_UP} / {@link #WHEEL_DOWN}</td><td>the wheel, one code per direction</td></tr>
 * </table>
 *
 * <p><b>The codes are ids and never move.</b> A player's config stores the number, so renumbering
 * {@link #MOUSE_BASE} silently rebinds every mouse keybind on disk to something else.
 *
 * <p>The wheel is a press and nothing else: it has no held state, so {@link #isDown} answers
 * {@code false} for it and a feature built on "is this held" cannot use it. Those rows say so —
 * see {@code SettingRow.holdKeybind}.
 */
public final class Keys {

    /** Mouse buttons are stored as {@code MOUSE_BASE + button}, clear of every GLFW key code. */
    public static final int MOUSE_BASE = 10_000;

    /** Highest GLFW mouse button ({@code GLFW_MOUSE_BUTTON_LAST}): eight buttons, indices 0..7. */
    public static final int MOUSE_BUTTON_LAST = GLFW.GLFW_MOUSE_BUTTON_LAST;

    /**
     * The middle mouse button, as a bind code.
     *
     * <p>Named because it is the one button a feature ships <i>bound</i> to (Ping Marker), and a
     * config field defaulting to a bare {@code 10002} says nothing about what it is. Every other
     * button stays anonymous - {@link #ofMouseButton} builds those.
     */
    public static final int MOUSE_MIDDLE = MOUSE_BASE + GLFW.GLFW_MOUSE_BUTTON_MIDDLE;

    /** Wheel scrolled away from the player. Clear of {@link #MOUSE_BASE} + every button index. */
    public static final int WHEEL_UP = 10_100;

    /** Wheel scrolled towards the player. */
    public static final int WHEEL_DOWN = 10_101;

    private Keys() {
    }

    /** Whether {@code code} is one of the eight mouse buttons. */
    public static boolean isMouseButton(int code) {
        return code >= MOUSE_BASE && code <= MOUSE_BASE + MOUSE_BUTTON_LAST;
    }

    /** Whether {@code code} is a wheel direction. */
    public static boolean isWheel(int code) {
        return code == WHEEL_UP || code == WHEEL_DOWN;
    }

    /** The GLFW button index behind a mouse code, or {@code -1} when {@code code} is not one. */
    public static int mouseButton(int code) {
        return isMouseButton(code) ? code - MOUSE_BASE : -1;
    }

    /** The bind code for a GLFW mouse button, or {@code 0} (unbound) for a button we cannot store. */
    public static int ofMouseButton(int button) {
        return button >= 0 && button <= MOUSE_BUTTON_LAST ? MOUSE_BASE + button : 0;
    }

    /**
     * The bind code for a wheel direction, or {@code 0} when the wheel did not move vertically.
     *
     * <p>The raw direction, which is what <b>binding</b> one wants: the smallest flick the player
     * makes should name the direction. Firing a bind goes through {@link WheelNotches} instead, so
     * one turn of the wheel runs a keybind once.
     */
    public static int ofWheel(double yOffset) {
        if (yOffset > 0) {
            return WHEEL_UP;
        }
        return yOffset < 0 ? WHEEL_DOWN : 0;
    }

    /**
     * Whether the bound key / mouse button is currently held.
     *
     * <p>Mouse buttons are read straight from GLFW rather than from {@code MouseHandler}'s
     * left/right/middle flags: those are only updated while no screen is open, so a hold-and-click
     * feature inside a container (Slot Lock) would never see the button go down.
     */
    public static boolean isDown(int code) {
        if (code == 0 || isWheel(code)) {
            return false;   // the wheel is a press, never a hold
        }
        Minecraft mc = Minecraft.getInstance();
        int button = mouseButton(code);
        if (button >= 0) {
            return GLFW.glfwGetMouseButton(mc.getWindow().handle(), button) == GLFW.GLFW_PRESS;
        }
        return InputConstants.isKeyDown(mc.getWindow(), code);
    }

    /** The display label for a bound code ("None" / "Mouse Left" / "Wheel Up" / a key name). */
    public static Component displayName(int code) {
        if (code == 0) {
            return Component.literal("None");
        }
        if (code == WHEEL_UP) {
            return Component.literal("Wheel Up");
        }
        if (code == WHEEL_DOWN) {
            return Component.literal("Wheel Down");
        }
        int button = mouseButton(code);
        if (button >= 0) {
            // 4..8 rather than 3..7: vanilla numbers the side buttons the way the hardware does,
            // and a keybind reading "Mouse 3" for the button labelled 4 is a support ticket.
            return Component.literal(switch (button) {
                case 0 -> "Mouse Left";
                case 1 -> "Mouse Right";
                case 2 -> "Mouse Middle";
                default -> "Mouse " + (button + 1);
            });
        }
        try {
            return InputConstants.Type.KEYSYM.getOrCreate(code).getDisplayName();
        } catch (Throwable t) {
            return Component.literal("Key " + code);
        }
    }

    /**
     * The modifier bits ({@code SHIFT|CTRL|ALT}) currently held, in GLFW's own layout.
     *
     * <p>GLFW hands modifiers to the key and mouse-button callbacks but not to the scroll one, so a
     * wheel bind has to ask the keyboard itself. Same bit values as {@code KeyEvent.modifiers()},
     * which is what {@link CommandKeybind#modifiers()} compares against.
     */
    public static int heldModifiers() {
        long window = Minecraft.getInstance().getWindow().handle();
        int modifiers = 0;
        if (isKeyDown(window, GLFW.GLFW_KEY_LEFT_SHIFT) || isKeyDown(window, GLFW.GLFW_KEY_RIGHT_SHIFT)) {
            modifiers |= CommandKeybind.MOD_SHIFT;
        }
        if (isKeyDown(window, GLFW.GLFW_KEY_LEFT_CONTROL) || isKeyDown(window, GLFW.GLFW_KEY_RIGHT_CONTROL)) {
            modifiers |= CommandKeybind.MOD_CTRL;
        }
        if (isKeyDown(window, GLFW.GLFW_KEY_LEFT_ALT) || isKeyDown(window, GLFW.GLFW_KEY_RIGHT_ALT)) {
            modifiers |= CommandKeybind.MOD_ALT;
        }
        return modifiers;
    }

    private static boolean isKeyDown(long window, int key) {
        return GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS;
    }
}
