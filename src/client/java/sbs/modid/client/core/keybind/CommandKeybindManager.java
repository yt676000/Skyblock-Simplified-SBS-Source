/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.keybind;

import net.minecraft.client.Minecraft;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.command.SBSCommands;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Central registry for the player's {@link CommandKeybind}s and the single place that
 * runs them.
 *
 * <p>The authoritative list lives inside the persisted {@link SBSConfig} (see
 * {@link #binds()}), so adding / removing / re-binding mutates the config directly and
 * a {@link #save()} flushes it to disk – there is no second copy to keep in sync.
 *
 * <p>Mirrors the lazy-singleton style of {@link sbs.modid.client.core.module.ModuleManager}
 * so any part of the client (the GUI, the key-press mixin) can reach it via
 * {@link #getInstance()}.
 */
public final class CommandKeybindManager {

    private static CommandKeybindManager instance;

    /** Timer for delayed actions; null until a keybind actually needs one (see {@link #scheduler()}). */
    private ScheduledExecutorService scheduler;

    private CommandKeybindManager() {
    }

    public static CommandKeybindManager getInstance() {
        if (instance == null) {
            instance = new CommandKeybindManager();
        }
        return instance;
    }

    /** The live, mutable backing list (stored in and persisted with the config). */
    private List<CommandKeybind> binds() {
        SBSConfig config = ConfigManager.getInstance().get();
        if (config.keybinds == null) {
            config.keybinds = new ArrayList<>();
        }
        return config.keybinds;
    }

    /** Read-only view of all keybinds in registration order. */
    public List<CommandKeybind> getKeybinds() {
        return Collections.unmodifiableList(binds());
    }

    /** Appends a fresh keybind row, seeded with one empty action to edit, and persists. */
    public CommandKeybind add() {
        CommandKeybind keybind = new CommandKeybind();
        keybind.addAction();
        binds().add(keybind);
        save();
        return keybind;
    }

    /** Removes the given keybind (if present) and persists. */
    public void remove(CommandKeybind keybind) {
        if (binds().remove(keybind)) {
            save();
        }
    }

    /** Moves {@code keybind} up (delta -1) or down (+1) in the list and persists. */
    public void move(CommandKeybind keybind, int delta) {
        List<CommandKeybind> list = binds();
        int i = list.indexOf(keybind);
        int j = i + delta;
        if (i >= 0 && j >= 0 && j < list.size()) {
            Collections.swap(list, i, j);
            save();
        }
    }

    /**
     * Assigns {@code keyCode} to {@code keybind}. Duplicate keys are now allowed – several keybinds
     * can share a key when they differ in modifiers or conditions (all matching ones are evaluated),
     * so this always accepts and returns {@code true}.
     */
    public boolean assignKey(CommandKeybind keybind, int keyCode) {
        keybind.setKeyCode(keyCode);
        save();
        return true;
    }

    /** Persists the current keybinds (best effort – never throws). */
    public void save() {
        try {
            ConfigManager.getInstance().save();
        } catch (Throwable t) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS] Failed to save command keybinds", t);
        }
    }

    /**
     * Runs every <b>enabled</b> keybind that matches {@code code} + {@code modifiers} and whose
     * conditions (island / area filter) are met right now. Called from
     * {@link KeybindDispatch} only while in-world with no screen open, with a key, a mouse button or
     * a wheel direction – all one {@link Keys} code here.
     */
    public void onPressed(int code, int modifiers) {
        if (code == CommandKeybind.UNBOUND) {
            return;
        }
        int mods = modifiers & (CommandKeybind.MOD_SHIFT | CommandKeybind.MOD_CTRL | CommandKeybind.MOD_ALT);
        // Copy first: an action may run something that mutates the list.
        for (CommandKeybind keybind : new ArrayList<>(binds())) {
            if (!keybind.enabled() || keybind.keyCode() != code || keybind.modifiers() != mods) {
                continue;
            }
            if (!KeybindLocation.conditionsMet(keybind)) {
                continue;
            }
            runActions(keybind);
        }
    }

    /**
     * Fires a keybind's actions in order, each after its own delay.
     *
     * <p>The text of every action is resolved <b>now</b>, at press time, rather than when it fires:
     * that keeps a cycle action's rotation in the order the presses happened, even if two presses
     * overlap while a long delay is still pending.
     *
     * <p>Delays accumulate, so action <i>n</i> runs at the sum of delays up to and including it. An
     * action with no text still contributes its delay, which makes an empty action a usable pause.
     * A zero total delay runs <b>inline on the calling thread</b> – byte for byte the path a keybind
     * took before delays existed, so nothing about existing keybinds changes.
     */
    private void runActions(CommandKeybind keybind) {
        long cumulative = 0;
        for (KeybindAction action : new ArrayList<>(keybind.actions())) {
            String text = action.nextText();
            cumulative += action.delayMs();
            if (text == null || text.isBlank()) {
                continue;
            }
            if (cumulative <= 0) {
                SBSCommands.run(text);
            } else {
                schedule(text, cumulative);
            }
        }
    }

    /**
     * Runs {@code text} after {@code delayMs}. The timer thread only waits – the command itself is
     * handed back to the client thread, because {@link SBSCommands#run(String)} can open screens and
     * touch the connection, neither of which is safe off-thread.
     */
    private void schedule(String text, long delayMs) {
        scheduler().schedule(
                () -> Minecraft.getInstance().execute(() -> SBSCommands.run(text)),
                delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * The shared delay timer, created on first use so a player without delays never spawns a thread.
     * Not synchronized on purpose: the only caller chain starts at the key-press mixin, which is
     * always the client thread.
     */
    private ScheduledExecutorService scheduler() {
        if (scheduler == null) {
            scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "SBS-KeybindDelay");
                thread.setDaemon(true);   // never keep the game alive on a pending delay
                return thread;
            });
        }
        return scheduler;
    }
}
