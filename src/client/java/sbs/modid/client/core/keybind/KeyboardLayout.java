/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.keybind;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The keyboard picture of the keyboard overview, in key units (one unit = one letter cap).
 *
 * <p><b>Positional, not lettered.</b> GLFW key codes name a <i>position</i> on a US board; what is
 * printed on that cap depends on the player's OS layout. So the caps carry codes and, for letters and
 * symbols, no label at all – the screen asks {@link Keys#displayName} at draw time, which goes through
 * GLFW's layout-aware key name. A German player therefore sees Z where a US player sees Y, from the
 * same layout. The shape is ISO (tall Enter, the extra key beside left Shift) because it is a superset
 * of ANSI: an ANSI board simply never sends {@code WORLD_2}.
 *
 * <p>Three widths, so the picture can shrink with the viewport instead of shrinking its caps below
 * readability: full, without the numpad, and main block only. The mouse block always stays – mouse
 * buttons carry a large share of SkyBlock binds.
 */
public final class KeyboardLayout {

    /** One cap. {@code label} is null for caps whose text comes from the OS layout. */
    public record Cap(int code, float x, float y, float w, float h, String label) {
    }

    /** Common board form factors, largest first ({@link #fitting} walks them in this order). */
    public enum Size {
        FULL("100%"), TKL("TKL"), COMPACT_75("75%"), COMPACT_60("60%");

        private final String label;

        Size(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final List<Cap> caps;
    private final float width;
    private final float height;

    private KeyboardLayout(List<Cap> caps) {
        this.caps = List.copyOf(caps);
        float w = 0;
        float h = 0;
        for (Cap cap : caps) {
            w = Math.max(w, cap.x() + cap.w());
            h = Math.max(h, cap.y() + cap.h());
        }
        this.width = w;
        this.height = h;
    }

    public List<Cap> caps() {
        return caps;
    }

    /** Width in units. */
    public float width() {
        return width;
    }

    /** Height in units. */
    public float height() {
        return height;
    }

    /** Every code that has a cap. */
    public Set<Integer> codes() {
        Set<Integer> out = new HashSet<>();
        for (Cap cap : caps) {
            out.add(cap.code());
        }
        return out;
    }

    /** The largest size whose width at {@code minUnit} pixels per unit fits {@code availablePx}. */
    public static KeyboardLayout fitting(float availablePx, float minUnit) {
        for (Size size : Size.values()) {
            KeyboardLayout layout = build(size);
            if (layout.width() * minUnit <= availablePx) {
                return layout;
            }
        }
        return build(Size.COMPACT_60);
    }

    public static KeyboardLayout build(Size size) {
        List<Cap> c = new ArrayList<>();

        boolean compact = size == Size.COMPACT_75;
        // Function row (a 60% board has none: everything moves up).
        float top = size == Size.COMPACT_60 ? 0 : compact ? 1 : 1.5f;
        if (size != Size.COMPACT_60) {
            add(c, GLFW.GLFW_KEY_ESCAPE, 0, 0, 1, "Esc");
            for (int i = 0; i < 12; i++) {
                float x = compact ? 1 + i : 2 + i + (i / 4) * 0.5f;
                add(c, GLFW.GLFW_KEY_F1 + i, x, 0, 1, "F" + (i + 1));
            }
        }
        if (compact) {
            add(c, GLFW.GLFW_KEY_PRINT_SCREEN, 13, 0, 1, "Prt");
            add(c, GLFW.GLFW_KEY_INSERT, 14, 0, 1, "Ins");
            add(c, GLFW.GLFW_KEY_DELETE, 15, 0, 1, "Del");
            add(c, GLFW.GLFW_KEY_HOME, 15, top, 1, "Hm");
            add(c, GLFW.GLFW_KEY_PAGE_UP, 15, top + 1, 1, "PU");
            add(c, GLFW.GLFW_KEY_PAGE_DOWN, 15, top + 2, 1, "PD");
            add(c, GLFW.GLFW_KEY_END, 15, top + 3, 1, "End");
        }

        // Number row.
        float y = top;
        add(c, GLFW.GLFW_KEY_GRAVE_ACCENT, 0, y, 1, null);
        for (int i = 0; i < 9; i++) {
            add(c, GLFW.GLFW_KEY_1 + i, 1 + i, y, 1, null);
        }
        add(c, GLFW.GLFW_KEY_0, 10, y, 1, null);
        add(c, GLFW.GLFW_KEY_MINUS, 11, y, 1, null);
        add(c, GLFW.GLFW_KEY_EQUAL, 12, y, 1, null);
        add(c, GLFW.GLFW_KEY_BACKSPACE, 13, y, 2, "Bksp");

        // Top letter row.
        y += 1;
        add(c, GLFW.GLFW_KEY_TAB, 0, y, 1.5f, "Tab");
        row(c, "QWERTYUIOP", 1.5f, y);
        add(c, GLFW.GLFW_KEY_LEFT_BRACKET, 11.5f, y, 1, null);
        add(c, GLFW.GLFW_KEY_RIGHT_BRACKET, 12.5f, y, 1, null);
        c.add(new Cap(GLFW.GLFW_KEY_ENTER, 13.75f, y, 1.25f, 2, "Enter"));

        // Home row.
        y += 1;
        add(c, GLFW.GLFW_KEY_CAPS_LOCK, 0, y, 1.75f, "Caps");
        row(c, "ASDFGHJKL", 1.75f, y);
        add(c, GLFW.GLFW_KEY_SEMICOLON, 10.75f, y, 1, null);
        add(c, GLFW.GLFW_KEY_APOSTROPHE, 11.75f, y, 1, null);
        add(c, GLFW.GLFW_KEY_BACKSLASH, 12.75f, y, 1, null);

        // Bottom letter row.
        y += 1;
        add(c, GLFW.GLFW_KEY_LEFT_SHIFT, 0, y, 1.25f, "Shift");
        add(c, GLFW.GLFW_KEY_WORLD_2, 1.25f, y, 1, null);
        row(c, "ZXCVBNM", 2.25f, y);
        add(c, GLFW.GLFW_KEY_COMMA, 9.25f, y, 1, null);
        add(c, GLFW.GLFW_KEY_PERIOD, 10.25f, y, 1, null);
        add(c, GLFW.GLFW_KEY_SLASH, 11.25f, y, 1, null);
        add(c, GLFW.GLFW_KEY_RIGHT_SHIFT, 12.25f, y, compact ? 1.75f : 2.75f, "Shift");
        if (compact) {
            add(c, GLFW.GLFW_KEY_UP, 14, y, 1, "↑");
        }

        // Space row.
        y += 1;
        add(c, GLFW.GLFW_KEY_LEFT_CONTROL, 0, y, 1.25f, "Ctrl");
        add(c, GLFW.GLFW_KEY_LEFT_SUPER, 1.25f, y, 1.25f, "Win");
        add(c, GLFW.GLFW_KEY_LEFT_ALT, 2.5f, y, 1.25f, "Alt");
        add(c, GLFW.GLFW_KEY_SPACE, 3.75f, y, 6.25f, "Space");
        if (compact) {
            add(c, GLFW.GLFW_KEY_RIGHT_ALT, 10, y, 1, "AltGr");
            add(c, GLFW.GLFW_KEY_RIGHT_CONTROL, 11, y, 1, "Ctrl");
            add(c, GLFW.GLFW_KEY_LEFT, 12, y, 1, "←");
            add(c, GLFW.GLFW_KEY_DOWN, 13, y, 1, "↓");
            add(c, GLFW.GLFW_KEY_RIGHT, 14, y, 1, "→");
            return withMouse(c, 16, top);
        }
        add(c, GLFW.GLFW_KEY_RIGHT_ALT, 10, y, 1.25f, "AltGr");
        add(c, GLFW.GLFW_KEY_RIGHT_SUPER, 11.25f, y, 1.25f, "Win");
        add(c, GLFW.GLFW_KEY_MENU, 12.5f, y, 1.25f, "Menu");
        add(c, GLFW.GLFW_KEY_RIGHT_CONTROL, 13.75f, y, 1.25f, "Ctrl");

        float next = 15;
        if (size == Size.FULL || size == Size.TKL) {
            float n = 15.5f;
            add(c, GLFW.GLFW_KEY_PRINT_SCREEN, n, 0, 1, "Prt");
            add(c, GLFW.GLFW_KEY_SCROLL_LOCK, n + 1, 0, 1, "Scr");
            add(c, GLFW.GLFW_KEY_PAUSE, n + 2, 0, 1, "Pse");
            add(c, GLFW.GLFW_KEY_INSERT, n, 1.5f, 1, "Ins");
            add(c, GLFW.GLFW_KEY_HOME, n + 1, 1.5f, 1, "Hm");
            add(c, GLFW.GLFW_KEY_PAGE_UP, n + 2, 1.5f, 1, "PU");
            add(c, GLFW.GLFW_KEY_DELETE, n, 2.5f, 1, "Del");
            add(c, GLFW.GLFW_KEY_END, n + 1, 2.5f, 1, "End");
            add(c, GLFW.GLFW_KEY_PAGE_DOWN, n + 2, 2.5f, 1, "PD");
            add(c, GLFW.GLFW_KEY_UP, n + 1, 4.5f, 1, "↑");
            add(c, GLFW.GLFW_KEY_LEFT, n, 5.5f, 1, "←");
            add(c, GLFW.GLFW_KEY_DOWN, n + 1, 5.5f, 1, "↓");
            add(c, GLFW.GLFW_KEY_RIGHT, n + 2, 5.5f, 1, "→");
            next = n + 3;
        }
        if (size == Size.FULL) {
            float p = next + 0.5f;
            add(c, GLFW.GLFW_KEY_NUM_LOCK, p, 1.5f, 1, "Num");
            add(c, GLFW.GLFW_KEY_KP_DIVIDE, p + 1, 1.5f, 1, "/");
            add(c, GLFW.GLFW_KEY_KP_MULTIPLY, p + 2, 1.5f, 1, "*");
            add(c, GLFW.GLFW_KEY_KP_SUBTRACT, p + 3, 1.5f, 1, "-");
            add(c, GLFW.GLFW_KEY_KP_7, p, 2.5f, 1, "7");
            add(c, GLFW.GLFW_KEY_KP_8, p + 1, 2.5f, 1, "8");
            add(c, GLFW.GLFW_KEY_KP_9, p + 2, 2.5f, 1, "9");
            c.add(new Cap(GLFW.GLFW_KEY_KP_ADD, p + 3, 2.5f, 1, 2, "+"));
            add(c, GLFW.GLFW_KEY_KP_4, p, 3.5f, 1, "4");
            add(c, GLFW.GLFW_KEY_KP_5, p + 1, 3.5f, 1, "5");
            add(c, GLFW.GLFW_KEY_KP_6, p + 2, 3.5f, 1, "6");
            add(c, GLFW.GLFW_KEY_KP_1, p, 4.5f, 1, "1");
            add(c, GLFW.GLFW_KEY_KP_2, p + 1, 4.5f, 1, "2");
            add(c, GLFW.GLFW_KEY_KP_3, p + 2, 4.5f, 1, "3");
            c.add(new Cap(GLFW.GLFW_KEY_KP_ENTER, p + 3, 4.5f, 1, 2, "Ent"));
            add(c, GLFW.GLFW_KEY_KP_0, p, 5.5f, 2, "0");
            add(c, GLFW.GLFW_KEY_KP_DECIMAL, p + 2, 5.5f, 1, ".");
            next = p + 4;
        }

        return withMouse(c, next, top);
    }

    /** The mouse block: buttons, wheel, side buttons. {@code top} is where the main rows start. */
    private static KeyboardLayout withMouse(List<Cap> c, float next, float top) {
        float m = next + 0.5f;
        float o = Math.max(0, top - 1.5f);   // follows the main rows up, never above the board
        add(c, Keys.MOUSE_BASE + 5, m, o + 0, 1, "M6");
        add(c, Keys.MOUSE_BASE + 6, m + 1, o + 0, 1, "M7");
        add(c, Keys.MOUSE_BASE + 7, m + 2, o + 0, 1, "M8");
        c.add(new Cap(Keys.MOUSE_BASE + GLFW.GLFW_MOUSE_BUTTON_LEFT, m, o + 1.5f, 1.5f, 2, "LMB"));
        c.add(new Cap(Keys.MOUSE_BASE + GLFW.GLFW_MOUSE_BUTTON_RIGHT, m + 1.5f, o + 1.5f, 1.5f, 2, "RMB"));
        add(c, Keys.WHEEL_UP, m, o + 3.5f, 1, "W↑");
        add(c, Keys.MOUSE_MIDDLE, m + 1, o + 3.5f, 1, "MMB");
        add(c, Keys.WHEEL_DOWN, m + 2, o + 3.5f, 1, "W↓");
        c.add(new Cap(Keys.MOUSE_BASE + 3, m, o + 4.5f, 1.5f, 2, "M4"));
        c.add(new Cap(Keys.MOUSE_BASE + 4, m + 1.5f, o + 4.5f, 1.5f, 2, "M5"));
        return new KeyboardLayout(c);
    }

    private static void row(List<Cap> c, String letters, float x, float y) {
        for (int i = 0; i < letters.length(); i++) {
            add(c, letters.charAt(i), x + i, y, 1, null);   // GLFW_KEY_A..Z equal their ASCII
        }
    }

    private static void add(List<Cap> c, int code, float x, float y, float w, String label) {
        c.add(new Cap(code, x, y, w, 1, label));
    }
}
