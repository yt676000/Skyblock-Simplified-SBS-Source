/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.sound;

import net.minecraft.client.resources.sounds.SoundInstance;
import sbs.modid.SkyblockSimplifiedSBS;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The one place features subscribe to sounds, so there is never a second mixin on the sound path.
 *
 * <p>{@code SoundEngine.play} is the funnel every sound in the game passes through, which makes it
 * both the only useful place to listen and the worst place to be careless. This exists so that
 * "I need to hear sounds" costs a listener registration rather than another injection into the
 * hottest callback in the client.
 *
 * <p><b>{@link #ACTIVE} is the whole performance design.</b> While nothing is subscribed the mixin
 * reads one static boolean and returns; the list is never touched, no iterator is allocated, and no
 * listener code runs. Features are expected to subscribe only while they are actually in use - the
 * beacon reader subscribes on the marsh and unsubscribes when the player leaves - so on a normal
 * server this path stays free.
 *
 * <p><b>A listener must not do real work here.</b> It is called on the client thread, once per sound,
 * before the engine has done anything. Record and return; anything expensive belongs on a tick. A
 * throwing listener is caught and dropped rather than being allowed to break every sound in the game,
 * and it is logged, because a listener that silently stops working is worse than one that stops
 * loudly.
 */
public final class SoundListeners {

    /** Something is subscribed. Read by the mixin before anything else on a very hot path. */
    public static volatile boolean ACTIVE;

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    /** What a feature implements to hear sounds. */
    @FunctionalInterface
    public interface Listener {
        /**
         * One sound, about to play.
         *
         * @param instance carries the id, the pitch, the volume and the position - see
         *                 {@link NotePitch} for turning the pitch into a note
         */
        void onSound(SoundInstance instance);
    }

    private SoundListeners() {
    }

    /** Starts delivering sounds to {@code listener}. Idempotent. */
    public static void add(Listener listener) {
        if (listener == null || LISTENERS.contains(listener)) {
            return;
        }
        LISTENERS.add(listener);
        ACTIVE = true;
    }

    /** Stops delivering to {@code listener}, and shuts the path down again when it was the last. */
    public static void remove(Listener listener) {
        if (listener == null) {
            return;
        }
        LISTENERS.remove(listener);
        ACTIVE = !LISTENERS.isEmpty();
    }

    /** Called by the mixin, only while {@link #ACTIVE}. */
    public static void dispatch(SoundInstance instance) {
        if (instance == null) {
            return;
        }
        for (Listener listener : LISTENERS) {
            try {
                listener.onSound(instance);
            } catch (Throwable t) {
                SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Sound] A sound listener threw: {}", t.toString());
            }
        }
    }
}
