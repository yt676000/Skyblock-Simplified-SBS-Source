/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.window;

import com.google.gson.reflect.TypeToken;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.SBSFiles;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Remembers where a floating window was left – position, size and whether it was minimized – across
 * restarts, in {@code config/sbs/gui/windows.json} beside the HUD and pause-menu layouts.
 *
 * <p>These windows used to keep that state in a field, so it survived a Bazaar re-entry and nothing
 * else: every restart put the Best Flips window back in the middle of the screen and re-expanded a
 * window the player had deliberately collapsed. A window's spot is a preference like any other, and
 * the player sets it by dragging rather than in the config screen.
 *
 * <p>One instance per window, held by the window itself; they share the one file, keyed by
 * {@link FloatingWindows.Layer} so the id is the enum name rather than a display string. A window
 * calls {@link #restore} once from its render path (the file is read on that first call, never at
 * class-load time – there is no game directory to read from during offline tooling) and
 * {@link #remember} when a drag, resize or minimize <i>finishes</i>. Writes are coalesced by
 * {@link #tick()}: a drag costs one file write, not one per frame.
 *
 * <p>Client thread only, like everything else in the render and input path.
 */
public final class WindowMemory {

    /** One window's remembered state. Plain mutable fields: this is exactly what Gson reads. */
    public static final class State {
        public int x;
        public int y;
        public int w;
        public int h;
        public boolean minimized;

        /** The remembered width, or {@code fallback} for a window that stores no size. */
        public int width(int fallback) {
            return w > 0 ? w : fallback;
        }

        /** The remembered height, or {@code fallback} for a window that stores no size. */
        public int height(int fallback) {
            return h > 0 ? h : fallback;
        }
    }

    private static final Type MAP_TYPE = new TypeToken<LinkedHashMap<String, State>>() {
    }.getType();

    /**
     * A coordinate past this is not somewhere a window was dragged, it is a corrupt or hand-edited
     * file – and one that would be remembered forever, since every window clamps into the viewport
     * on render without ever writing the clamped value back.
     */
    private static final int SANE_COORD = 20_000;

    /** How long a change is held before it reaches disk – one write per drag, not one per pixel. */
    private static final long WRITE_DELAY_MS = 500;

    private static Map<String, State> states;
    private static boolean dirty;
    private static long dirtySince;

    private final String key;
    private boolean restored;

    public WindowMemory(FloatingWindows.Layer window) {
        this(window.name());
    }

    /**
     * A window that is not one of the {@link FloatingWindows} layers - Inventory Window keeps one
     * entry per kind of screen. The key is a stable id, never display text.
     */
    public WindowMemory(String key) {
        this.key = key;
    }

    /**
     * The remembered state, or {@code null} when nothing was saved - read on every call, for a
     * window that is rebuilt each time its screen opens rather than restored once.
     */
    public State current() {
        return map().get(key);
    }

    /** Drops the remembered state, so the window opens at its default spot next time. */
    public void forget() {
        if (map().remove(key) != null) {
            markDirty();
        }
    }

    /**
     * Hands the remembered state to {@code apply} on the first call and does nothing afterwards, so
     * this is safe to call every frame from a render method. A window that has never been saved is
     * left at whatever default placement it computes for itself.
     */
    public void restore(Consumer<State> apply) {
        if (restored) {
            return;
        }
        restored = true;
        State state = map().get(key);
        if (state != null) {
            apply.accept(state);
        }
    }

    /** Remembers position, size and collapsed state; call when a drag or resize has finished. */
    public void remember(int x, int y, int w, int h, boolean minimized) {
        State state = writable(x, y);
        if (state == null) {
            return;
        }
        state.w = w;
        state.h = h;
        state.minimized = minimized;
        markDirty();
    }

    /** Remembers position and collapsed state only – for a window whose size is not adjustable. */
    public void remember(int x, int y, boolean minimized) {
        State state = writable(x, y);
        if (state == null) {
            return;
        }
        state.minimized = minimized;
        markDirty();
    }

    /**
     * The entry to write into, with {@code x}/{@code y} already applied – or {@code null} when the
     * position is one no drag can produce: {@link Integer#MIN_VALUE} is every window's "not placed
     * yet" marker, and saving it would make the window unplaceable on the next start.
     */
    private State writable(int x, int y) {
        if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE
                || Math.abs(x) > SANE_COORD || Math.abs(y) > SANE_COORD) {
            return null;
        }
        State state = map().computeIfAbsent(key, k -> new State());
        state.x = x;
        state.y = y;
        return state;
    }

    private static void markDirty() {
        if (!dirty) {
            dirty = true;
            dirtySince = System.currentTimeMillis();
        }
    }

    // ------------------------------------------------------------------
    // The shared file
    // ------------------------------------------------------------------

    /** Writes a pending change once it has settled; called once per client tick. */
    public static void tick() {
        if (!dirty || System.currentTimeMillis() - dirtySince < WRITE_DELAY_MS) {
            return;
        }
        dirty = false;
        save();
    }

    private static Map<String, State> map() {
        if (states == null) {
            load();
        }
        return states;
    }

    private static void load() {
        Path path = SBSFiles.windowsFile();
        try {
            if (Files.exists(path)) {
                try (Reader reader = Files.newBufferedReader(path)) {
                    Map<String, State> parsed = SBSFiles.GSON.fromJson(reader, MAP_TYPE);
                    if (parsed != null) {
                        states = parsed;
                        return;
                    }
                }
            }
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.warn(
                    "[SBS][UI] Could not read gui/windows.json - windows open at their default spot", e);
        }
        states = new LinkedHashMap<>();
    }

    private static void save() {
        Path path = SBSFiles.windowsFile();
        try {
            SBSFiles.ensureParent(path);
            try (Writer writer = Files.newBufferedWriter(path)) {
                SBSFiles.GSON.toJson(states, writer);
            }
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][UI] Failed to write gui/windows.json", e);
        }
    }
}
