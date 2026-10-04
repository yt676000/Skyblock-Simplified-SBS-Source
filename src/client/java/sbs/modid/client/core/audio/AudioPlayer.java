/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.audio;

import sbs.modid.SkyblockSimplifiedSBS;

import javax.sound.sampled.LineUnavailableException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The playback engine behind {@link SbsAudio}: one audio thread owns the output line outright.
 *
 * <p><b>Threading.</b> Opening the device, writing, draining and closing all run on the executor
 * passed in, which must run its tasks one at a time and in order. Nothing else ever touches the line,
 * so there is no lock - and in particular none the client thread could wait on. Opening a device can
 * take hundreds of milliseconds and {@code drain()} blocks for the whole tone; both used to happen
 * under a lock that {@link #tick} also took on the client thread, which froze the game for the length
 * of every alert sound. The client thread now only reads volatile fields and posts tasks.
 *
 * <p><b>Idle release.</b> {@link #tick} notices from the client thread that the line has been idle
 * for {@link #idleReleaseMs} and posts a release to the audio thread. The release checks again before
 * closing, since a ping queued in between makes the line busy again.
 */
final class AudioPlayer {

    /** The output device, abstracted so a test can stand in a slow fake. */
    interface Device {
        /**
         * Opens a line ready for writing.
         *
         * @throws LineUnavailableException for a passing failure (exclusive mode, no device yet)
         * @throws Unsupported              when this system can never play the alert format
         */
        Line open() throws LineUnavailableException, Unsupported;
    }

    /** An open output line. Used only on the audio thread. */
    interface Line {
        /**
         * Plays {@code pcm} at {@code volumePercent} and returns once it has finished sounding.
         *
         * @throws IllegalArgumentException or {@link IllegalStateException} when the device went
         *                                  away mid-write
         */
        void play(byte[] pcm, int volumePercent);

        void close();
    }

    /** The independent output can never work in this session. */
    static final class Unsupported extends Exception {
        Unsupported(String reason, Throwable cause) {
            super(reason, cause);
        }
    }

    private final Device device;
    private final LongSupplier clock;
    private final Executor audioThread;
    private final long idleReleaseMs;

    /** The open line, or {@code null} while idle. Audio thread only. */
    private Line line;

    /** Mirrors {@code line != null} for the client thread. */
    private volatile boolean lineOpen;
    private volatile long lastUsedAt;

    /** True once this session has given up on the independent output entirely. */
    private volatile boolean unavailable;

    /** Keeps a burst of pings from queueing up behind each other. */
    private final AtomicBoolean playing = new AtomicBoolean();

    /** Keeps {@link #tick} from posting a second release while one is already queued. */
    private final AtomicBoolean releaseQueued = new AtomicBoolean();

    /** So the fallback explanation is logged once per session, not once per alert. */
    private final AtomicBoolean loggedFallback = new AtomicBoolean();

    AudioPlayer(Device device, LongSupplier clock, Executor audioThread, long idleReleaseMs) {
        this.device = device;
        this.clock = clock;
        this.audioThread = audioThread;
        this.idleReleaseMs = idleReleaseMs;
    }

    /**
     * Queues a tone on the audio thread. Returns immediately; {@code pcm} is rendered there too.
     *
     * @param fallback run on the audio thread when the line could not play the tone
     * @return {@code false} when the output is known to be unavailable and nothing was queued
     */
    boolean play(Supplier<byte[]> pcm, int volumePercent, Runnable fallback) {
        if (unavailable) {
            return false;
        }
        if (!playing.compareAndSet(false, true)) {
            return true;   // a ping is already sounding; a second one on top of it is just noise
        }
        try {
            audioThread.execute(() -> {
                try {
                    if (!playOnAudioThread(pcm.get(), volumePercent)) {
                        fallback.run();
                    }
                } finally {
                    playing.set(false);
                }
            });
        } catch (RejectedExecutionException e) {
            playing.set(false);
            warnOnce("the audio thread refused the ping", e);
            return false;
        }
        return true;
    }

    /** Writes one tone to the line, reopening the device if it is idle or was lost. */
    private boolean playOnAudioThread(byte[] pcm, int volumePercent) {
        Line open = openLine();
        if (open == null) {
            return false;
        }
        try {
            open.play(pcm, volumePercent);
            lastUsedAt = clock.getAsLong();
            return true;
        } catch (IllegalArgumentException | IllegalStateException e) {
            // The device went away mid-write (unplugged, Bluetooth switched, driver reset). Drop
            // the handle so the next ping opens whatever is the default device by then.
            closeLine();
            warnOnce("the audio device was lost mid-playback", e);
            return false;
        }
    }

    /** The open line, opening one if needed. {@code null} when no device will take it. */
    private Line openLine() {
        if (line != null) {
            return line;
        }
        try {
            line = device.open();
            lastUsedAt = clock.getAsLong();
            lineOpen = true;
            return line;
        } catch (LineUnavailableException e) {
            // Another application holds the device in exclusive mode, or there is none. Not fatal
            // and not permanent - the next ping tries again, since exclusive mode is usually a
            // passing state.
            warnOnce("the audio device is unavailable (another application may hold it)", e);
            return null;
        } catch (Unsupported e) {
            unavailable = true;
            warnOnce(e.getMessage(), e.getCause());
            return null;
        }
    }

    /**
     * Posts a release once the line has been idle long enough. Client thread; never blocks - it reads
     * two volatile fields and at most queues one task.
     */
    void tick() {
        if (!lineOpen || clock.getAsLong() - lastUsedAt <= idleReleaseMs) {
            return;
        }
        if (!releaseQueued.compareAndSet(false, true)) {
            return;
        }
        try {
            audioThread.execute(() -> {
                releaseQueued.set(false);
                // Checked again here: a ping that ran after the post has made the line busy again.
                if (line != null && clock.getAsLong() - lastUsedAt > idleReleaseMs) {
                    closeLine();
                }
            });
        } catch (RejectedExecutionException e) {
            releaseQueued.set(false);
        }
    }

    /** Drops the line. Audio thread only. */
    private void closeLine() {
        if (line == null) {
            return;
        }
        try {
            line.close();
        } catch (RuntimeException ignored) {
            // Closing a device that is already gone throws; there is nothing left to clean up.
        }
        line = null;
        lineOpen = false;
    }

    boolean unavailable() {
        return unavailable;
    }

    boolean lineOpen() {
        return lineOpen;
    }

    /** Logs the first failure this session; returns whether this call was the one that logged. */
    boolean markFallbackLogged() {
        return loggedFallback.compareAndSet(false, true);
    }

    private void warnOnce(String reason, Throwable cause) {
        if (markFallbackLogged()) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Audio] {} - alert pings fall back to the "
                    + "game's sound engine.", reason, cause);
        }
    }
}
