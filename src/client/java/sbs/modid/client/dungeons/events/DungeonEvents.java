/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.events;

import net.minecraft.core.BlockPos;
import sbs.modid.SkyblockSimplifiedSBS;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The dungeon event layer: a small, decoupled publish/subscribe hub so features react to
 * <b>room enter/leave</b>, <b>secret found</b> and <b>phase change</b> without knowing about their
 * producers. Producers (the room tracker, the state manager, the secret detectors) call the
 * {@code fire…} methods; consumers implement {@link Listener} (all methods default no-op) and
 * {@link #register}.
 *
 * <p>Fires on the client thread only (tick / chat), so listeners need no locking but must not block.
 * Each callback is guarded so one bad listener cannot break the others.
 */
public final class DungeonEvents {

    /** The coarse phase of a dungeon run. */
    public enum Phase {
        /** Before the gate opens - inside the entrance, run not started. */
        START,
        /** The exploration/clear phase. */
        RUN,
        /** The boss room. */
        BOSS
    }

    /** A collected secret, with what kind it was and where (world block), for consumers that care. */
    public record Secret(String kind, BlockPos world) {
    }

    /** Consumer contract; every method is optional (default no-op). */
    public interface Listener {
        default void onRoomEnter(String roomName) {
        }

        default void onRoomLeave(String roomName) {
        }

        default void onSecretFound(Secret secret) {
        }

        default void onPhaseChange(Phase previous, Phase next) {
        }
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private DungeonEvents() {
    }

    public static void register(Listener listener) {
        if (listener != null) {
            LISTENERS.add(listener);
        }
    }

    public static void fireRoomEnter(String roomName) {
        for (Listener listener : LISTENERS) {
            guard(() -> listener.onRoomEnter(roomName));
        }
    }

    public static void fireRoomLeave(String roomName) {
        for (Listener listener : LISTENERS) {
            guard(() -> listener.onRoomLeave(roomName));
        }
    }

    public static void fireSecretFound(Secret secret) {
        for (Listener listener : LISTENERS) {
            guard(() -> listener.onSecretFound(secret));
        }
    }

    public static void firePhaseChange(Phase previous, Phase next) {
        for (Listener listener : LISTENERS) {
            guard(() -> listener.onPhaseChange(previous, next));
        }
    }

    private static void guard(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            SkyblockSimplifiedSBS.LOGGER.debug("[SBS][Dungeon] event listener failed: {}", e.toString());
        }
    }
}
