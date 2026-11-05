/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.world;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import sbs.modid.SkyblockSimplifiedSBS;

/**
 * The one place world-bound state is told that its world is gone.
 *
 * <p>Three things end a world, and each is seen by a different hook: the client-tick edge where
 * the player disappears ({@link Transition#WORLD_CHANGE}), the connection closing
 * ({@link Transition#DISCONNECT}) and the server sending the client back into configuration for a
 * proxy transfer ({@link Transition#SERVER_SWITCH}). One leave usually trips more than one of them,
 * so the registry fires once per leave: the first hook to see it wins, and the rest are no-ops
 * until a player exists again.
 *
 * <p>Every fire bumps {@link #generation()}. Multi-tick or async work captures it when it starts
 * and drops its result if the number moved - a result computed for a world that has since closed
 * is never applied.
 *
 * <p>Main thread only. {@link #fire} re-posts itself when called from anywhere else.
 */
public final class WorldTransitions {

    /** What ended the world. Listeners that do not care treat every kind the same. */
    public enum Transition {
        WORLD_CHANGE,
        DISCONNECT,
        SERVER_SWITCH
    }

    /** World-bound state. Called on the main thread, once per leave, in registration order. */
    @FunctionalInterface
    public interface WorldScoped {
        void onTransition(Transition kind);
    }

    private static final List<WorldScoped> LISTENERS = new ArrayList<>();

    /** Read from worker threads; written on the main thread only. */
    private static volatile long generation;

    /** A player existed since the last fire - i.e. there is a world to leave. */
    private static boolean armed;

    /** Whether the previous tick had a player - a true->false edge is a world leave. */
    private static boolean hadPlayer;

    private WorldTransitions() {}

    /**
     * Registers world-bound state. Call at init; order is the order listeners run in. Pass a lambda
     * that resolves the singleton inside it, so registering does not load the feature's class.
     */
    public static void register(WorldScoped listener) {
        LISTENERS.add(listener);
    }

    /** Bumped on every transition. Compare against a value captured when the work started. */
    public static long generation() {
        return generation;
    }

    /** Client tick: arms on a player, fires {@link Transition#WORLD_CHANGE} on the leave edge. */
    public static void tick(boolean hasPlayer) {
        if (hasPlayer) {
            armed = true;
        } else if (hadPlayer) {
            fire(Transition.WORLD_CHANGE);
        }
        hadPlayer = hasPlayer;
    }

    /** Ends the current world. A no-op when it has already ended and no player has appeared since. */
    public static void fire(Transition kind) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(() -> fire(kind));
            return;
        }
        if (!armed) {
            return;
        }
        armed = false;
        generation++;
        for (int i = 0, n = LISTENERS.size(); i < n; i++) {
            try {
                LISTENERS.get(i).onTransition(kind);
            } catch (Throwable t) {
                SkyblockSimplifiedSBS.LOGGER.warn("[SBS][World] {} hook failed: {}", kind, t.toString());
            }
        }
    }
}
