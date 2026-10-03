/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.keybind;

import net.minecraft.network.chat.Component;

/**
 * A single command keybind: one input – a key, a mouse button or a wheel direction – paired with an
 * ordered list of {@link KeybindAction}s that run when it is pressed during normal gameplay. Each
 * action carries its own delay, so one key can drive a timed sequence.
 *
 * <p>Intentionally a small POJO so {@link sbs.modid.client.core.config.ConfigManager}'s
 * Gson can (de)serialize it directly as part of the persisted config. Only the raw
 * {@link #keyCode} (a {@link Keys} bind code) and the actions are stored; the human-readable name is
 * derived on the fly via {@link Keys#displayName} so nothing display-related needs persisting.
 */
public final class CommandKeybind {

    /** Sentinel {@link #keyCode} meaning "no key assigned yet". Matches GLFW's unknown key. */
    public static final int UNBOUND = -1;

    /** Modifier bit masks – identical to GLFW's ({@code KeyEvent.modifiers()}), so they compare directly. */
    public static final int MOD_SHIFT = 1;
    public static final int MOD_CTRL = 2;
    public static final int MOD_ALT = 4;

    /** Master on/off toggle for this keybind (Overview switch). */
    private boolean enabled = true;

    /** Optional short display name shown in the overview; falls back to the first action. */
    private String label = "";

    /** The bound input as a {@link Keys} code (key, mouse button or wheel); {@link #UNBOUND} when unset. */
    private int keyCode = UNBOUND;

    /** Required modifier keys ({@link #MOD_SHIFT} | {@link #MOD_CTRL} | {@link #MOD_ALT}); 0 = none. */
    private int modifiers = 0;

    /** What this keybind runs, in order. Each entry waits its own delay first. */
    private java.util.List<KeybindAction> actions = new java.util.ArrayList<>();

    /** Only run when the current SkyBlock area name contains this text (case-insensitive); "" = anywhere. */
    private String islandFilter = "";

    /** Optional area (bounding box) the player must be inside for this keybind to run. */
    private AreaFilter area = new AreaFilter();

    // ------------------------------------------------------------------
    // Legacy single-action fields – read once by ConfigManager's migration, then nulled.
    //
    // The names must stay exactly as they were (Gson maps by field name) and the defaults must be
    // null, so "absent from an already-migrated config" is distinguishable from "an old config that
    // really did store an empty command".
    // ------------------------------------------------------------------

    /** @deprecated legacy {@code "command"|"cycle"}; migrated into {@link #actions}. */
    @Deprecated
    private String action;

    /** @deprecated legacy single command; migrated into {@link #actions}. */
    @Deprecated
    private String command;

    /** @deprecated legacy cycle list; migrated into {@link #actions}. */
    @Deprecated
    private java.util.List<String> messages;

    public CommandKeybind() {
    }

    /**
     * Folds a pre-delay config's single action into {@link #actions}, and returns whether anything
     * was migrated. The old shape stored the action inline on the keybind; the new one always uses
     * the list, with the migrated step keeping delay 0 so it fires exactly as it did before.
     */
    @SuppressWarnings("deprecation")
    public boolean migrateLegacyAction() {
        if (action == null && command == null && messages == null) {
            return false;   // already on the new shape
        }
        if (actions().isEmpty()) {
            actions().add(new KeybindAction(
                    KeybindAction.TYPE_CYCLE.equals(action)
                            ? KeybindAction.TYPE_CYCLE : KeybindAction.TYPE_COMMAND,
                    command, messages, 0));
        }
        // Null them so Gson drops the legacy keys from config.json on the next write.
        action = null;
        command = null;
        messages = null;
        return true;
    }

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String label() {
        return label == null ? "" : label;
    }

    public void setLabel(String label) {
        this.label = label == null ? "" : label;
    }

    public int modifiers() {
        return modifiers;
    }

    public void setModifiers(int modifiers) {
        this.modifiers = modifiers & (MOD_SHIFT | MOD_CTRL | MOD_ALT);
    }

    /** The live, mutable action list; never null. */
    public java.util.List<KeybindAction> actions() {
        if (actions == null) {
            actions = new java.util.ArrayList<>();
        }
        return actions;
    }

    /** Appends a fresh action and returns it. */
    public KeybindAction addAction() {
        KeybindAction added = new KeybindAction();
        actions().add(added);
        return added;
    }

    /** Removes {@code action} – but never the last one, so a keybind always has something to edit. */
    public void removeAction(KeybindAction action) {
        if (actions().size() > 1) {
            actions().remove(action);
        }
    }

    /** Moves {@code action} up (delta -1) or down (+1) within the list; the order is the run order. */
    public void moveAction(KeybindAction action, int delta) {
        java.util.List<KeybindAction> list = actions();
        int i = list.indexOf(action);
        int j = i + delta;
        if (i >= 0 && j >= 0 && j < list.size()) {
            java.util.Collections.swap(list, i, j);
        }
    }

    /** Total time from the key press until the last action has fired, in milliseconds. */
    public int totalDelayMs() {
        int total = 0;
        for (KeybindAction step : actions()) {
            total += step.delayMs();
        }
        return total;
    }

    public String islandFilter() {
        return islandFilter == null ? "" : islandFilter;
    }

    public void setIslandFilter(String islandFilter) {
        this.islandFilter = islandFilter == null ? "" : islandFilter.trim();
    }

    public AreaFilter area() {
        if (area == null) {
            area = new AreaFilter();
        }
        return area;
    }

    /**
     * A one-line summary of this keybind for the overview row: the label if set, otherwise the
     * first action – with a "+n more" hint once it is a sequence, so the overview shows at a glance
     * which keybinds do more than one thing.
     */
    public String summary() {
        if (!label().isEmpty()) {
            return label();
        }
        if (actions().isEmpty()) {
            return "(no actions)";
        }
        String first = actions().get(0).summary();
        int more = actions().size() - 1;
        return more > 0 ? first + " §8(+" + more + " more)" : first;
    }

    public int keyCode() {
        return keyCode;
    }

    public void setKeyCode(int keyCode) {
        this.keyCode = keyCode;
    }

    /** Whether a key has been assigned. */
    public boolean isBound() {
        return keyCode != UNBOUND;
    }

    /**
     * The localized, display-ready name of the assigned input (e.g. {@code G}, {@code Left Shift},
     * {@code Mouse 4}, {@code Wheel Up}), or a muted "None" placeholder when unbound.
     */
    public Component keyDisplayName() {
        return isBound() ? Keys.displayName(keyCode) : Component.literal("None");
    }

    /** Modifier prefix + key, e.g. "Shift + G" or "None" when unbound. */
    public Component keyComboName() {
        if (!isBound()) {
            return Component.literal("None");
        }
        return Component.literal(modifierPrefix() + keyDisplayName().getString());
    }

    /** Human-readable modifier prefix ("Shift + ", "Ctrl + Alt + ", or "" for none). */
    public String modifierPrefix() {
        StringBuilder sb = new StringBuilder();
        if ((modifiers & MOD_CTRL) != 0) {
            sb.append("Ctrl + ");
        }
        if ((modifiers & MOD_SHIFT) != 0) {
            sb.append("Shift + ");
        }
        if ((modifiers & MOD_ALT) != 0) {
            sb.append("Alt + ");
        }
        return sb.toString();
    }
}
