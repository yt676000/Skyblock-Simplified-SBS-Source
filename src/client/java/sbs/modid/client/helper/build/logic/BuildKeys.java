/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.build.logic;

import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/**
 * The one router for Build Tools' in-world keys and wheel. Each state that borrows keys - placing a
 * hologram, a pending edit preview, the build guide - registers a {@link Handler}; the first one that
 * wants the input takes it, and when none is active nothing is consumed at all.
 *
 * <p>A held key repeats only for movement (arrows, Page Up/Down): a repeating Enter must not apply two
 * edits, and a repeating R must not spin a build out from under the player ({@link #repeats}).
 */
public final class BuildKeys {

    /** One state that may consume keys. Registered once, asked on every in-world press. */
    public interface Handler {

        /**
         * Returns whether the key was consumed. {@code repeat} is a held key's auto-repeat: a handler
         * acts on it only for movement, but still consumes a key it owns so the game never sees half
         * a hold.
         */
        boolean onKey(int key, int modifiers, boolean repeat);

        /** Returns whether the wheel turn was consumed. */
        boolean onScroll(double yOffset, boolean shift);
    }

    private static final List<Handler> HANDLERS = new ArrayList<>();

    static {
        HANDLERS.add(new Handler() {
            @Override
            public boolean onKey(int key, int modifiers, boolean repeat) {
                return Placement.onKey(key, modifiers, repeat);
            }

            @Override
            public boolean onScroll(double yOffset, boolean shift) {
                return Placement.onScroll(yOffset, shift);
            }
        });
    }

    private BuildKeys() {
    }

    /** Adds a handler after the built-in ones. Call once, from the client thread. */
    public static void register(Handler handler) {
        HANDLERS.add(handler);
    }

    public static boolean onKey(int key, int modifiers, boolean repeat) {
        if (Freecam.cinematic()) {
            return false;   // a pure camera: no hologram, preview or guide keys
        }
        for (Handler handler : HANDLERS) {
            if (handler.onKey(key, modifiers, repeat)) {
                return true;
            }
        }
        return false;
    }

    public static boolean onScroll(double yOffset) {
        if (Freecam.cinematic()) {
            return Freecam.scrollSpeed(yOffset);
        }
        boolean shift = Minecraft.getInstance().hasShiftDown();
        for (Handler handler : HANDLERS) {
            if (handler.onScroll(yOffset, shift)) {
                return true;
            }
        }
        return false;
    }

    /** Keys whose auto-repeat means "keep going": the movement keys. */
    public static boolean repeats(int key) {
        return key == Placement.Keys.UP || key == Placement.Keys.DOWN || key == Placement.Keys.LEFT
                || key == Placement.Keys.RIGHT || key == Placement.Keys.PAGE_UP || key == Placement.Keys.PAGE_DOWN;
    }
}
