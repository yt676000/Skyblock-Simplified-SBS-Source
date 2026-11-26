/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.keybind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Every bind in the game, grouped by the key or mouse button it sits on – the model behind the
 * keyboard overview screen.
 *
 * <p>Pure on purpose: no Minecraft class is touched here, so the grouping and the conflict rule are
 * unit-tested. Reading the live binds (vanilla key mappings, module key rows, command keybinds, slot
 * hotkeys) is {@link KeyBindSources}' job; it hands this class a flat list.
 *
 * <p>Codes use the {@link Keys} encoding: a GLFW key code, {@link Keys#MOUSE_BASE} + button, or a
 * wheel direction. {@code 0} and {@link CommandKeybind#UNBOUND} mean "no key" and are dropped.
 */
public final class KeyBindOverview {

    /** Where a bind comes from. The label is what the player reads next to the bind. */
    public enum Source {
        MINECRAFT("Controls"),
        MODULE("SBS Setting"),
        COMMAND_KEYBIND("Command Keybind"),
        SLOT_HOTKEY("Slot Hotkey");

        private final String label;

        Source(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * How a key is used.
     *
     * <ul>
     *   <li>{@code SHARED} – several binds, but each needs a different modifier combination, so one
     *       press only ever fires one of them.</li>
     *   <li>{@code CONFLICT} – at least two binds fire on the very same press.</li>
     * </ul>
     */
    public enum Status { FREE, BOUND, SHARED, CONFLICT }

    /**
     * One bind.
     *
     * @param code      the key, in the {@link Keys} encoding
     * @param modifiers GLFW modifier bits ({@link CommandKeybind#MOD_SHIFT} etc.), 0 for none
     * @param source    which system owns it
     * @param group     the finer origin the player recognises – a module name, a controls category
     * @param name      what the bind does
     * @param open      opens the place where it is set, or {@code null} when there is none
     */
    public record Bind(int code, int modifiers, Source source, String group, String name, Runnable open) {

        /** {@code "Shift + "}-style prefix for the modifiers, empty for none. */
        public String modifierPrefix() {
            StringBuilder out = new StringBuilder();
            if ((modifiers & CommandKeybind.MOD_CTRL) != 0) {
                out.append("Ctrl + ");
            }
            if ((modifiers & CommandKeybind.MOD_SHIFT) != 0) {
                out.append("Shift + ");
            }
            if ((modifiers & CommandKeybind.MOD_ALT) != 0) {
                out.append("Alt + ");
            }
            return out.toString();
        }
    }

    private final Map<Integer, List<Bind>> byCode;

    private KeyBindOverview(Map<Integer, List<Bind>> byCode) {
        this.byCode = byCode;
    }

    /** Groups {@code binds} by key, dropping unbound ones. Order inside a key is input order. */
    public static KeyBindOverview of(List<Bind> binds) {
        Map<Integer, List<Bind>> grouped = new TreeMap<>();
        for (Bind bind : binds) {
            if (bind == null || bind.code() == 0 || bind.code() == CommandKeybind.UNBOUND) {
                continue;
            }
            grouped.computeIfAbsent(bind.code(), k -> new ArrayList<>()).add(bind);
        }
        grouped.replaceAll((k, v) -> List.copyOf(v));
        return new KeyBindOverview(Collections.unmodifiableMap(grouped));
    }

    /** The binds on {@code code}, empty when the key is free. */
    public List<Bind> on(int code) {
        return byCode.getOrDefault(code, List.of());
    }

    /** Every bound code, ascending. */
    public Set<Integer> codes() {
        return byCode.keySet();
    }

    /** Total number of binds collected. */
    public int size() {
        int n = 0;
        for (List<Bind> list : byCode.values()) {
            n += list.size();
        }
        return n;
    }

    /** How {@code code} is used. See {@link Status}. */
    public Status status(int code) {
        List<Bind> list = on(code);
        if (list.isEmpty()) {
            return Status.FREE;
        }
        if (list.size() == 1) {
            return Status.BOUND;
        }
        Set<Integer> seen = new HashSet<>();
        for (Bind bind : list) {
            if (!seen.add(bind.modifiers())) {
                return Status.CONFLICT;
            }
        }
        return Status.SHARED;
    }

    /** Whether any two binds on {@code code} fire on the same press. */
    public boolean conflicts(int code) {
        return status(code) == Status.CONFLICT;
    }

    /** Number of keys in {@link Status#CONFLICT}. */
    public int conflictCount() {
        int n = 0;
        for (int code : byCode.keySet()) {
            if (conflicts(code)) {
                n++;
            }
        }
        return n;
    }

    /** Bound codes that are not in {@code drawn} – keys the keyboard picture has no cap for. */
    public List<Integer> codesOutside(Set<Integer> drawn) {
        List<Integer> out = new ArrayList<>();
        for (int code : byCode.keySet()) {
            if (!drawn.contains(code)) {
                out.add(code);
            }
        }
        return out;
    }
}
