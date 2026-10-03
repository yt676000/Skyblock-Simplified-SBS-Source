/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.audio;

import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvents;
import sbs.modid.SkyblockSimplifiedSBS;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The mod's own audio output, independent of Minecraft's sound engine.
 *
 * <p><b>Why this exists.</b> Every alert ping used to be {@code player.playSound(...)}, which goes
 * through {@code SoundEngine.calculateVolume} and is therefore scaled by the category volume
 * <i>and</i> the master slider - so a player who mutes Minecraft to listen to something else hears
 * no alerts either, which is precisely when an alert matters most. This plays through
 * {@code javax.sound.sampled} instead: a different audio path entirely, with its own volume, that
 * Minecraft's sliders cannot touch.
 *
 * <p><b>No bundled assets.</b> The tones are synthesized here - a short two-note chime and a lower
 * warning blip - so there is no audio file to ship, decode or keep in sync with a resource pack.
 *
 * <p><b>Device handling.</b> The line is opened lazily on the first ping and released again after
 * {@link #IDLE_RELEASE_MS} of silence, so the mod never sits on a device handle it is not using -
 * that is what blocks other applications from taking exclusive mode and what makes a Bluetooth
 * switch or an unplugged headset painful. Every failure path (no device, exclusive mode, device
 * removed mid-session) falls back to Minecraft's own sound and says so in the log exactly once per
 * cause, so a silent alert is never silent about why.
 */
public final class SbsAudio {

    /** Sample rate of the generated tones. 44.1 kHz is what every output device accepts. */
    private static final float SAMPLE_RATE = 44_100f;

    /** Signed 16-bit mono, little endian - the most widely supported line format there is. */
    private static final AudioFormat FORMAT =
            new AudioFormat(SAMPLE_RATE, 16, 1, true, false);

    /** Release the device after this long without a ping. */
    private static final long IDLE_RELEASE_MS = 30_000L;

    /** Peak amplitude of a generated tone, well below clipping. */
    private static final double AMPLITUDE = 11_000.0;

    /** Fade in/out applied to every tone, so it starts and ends without a click. */
    private static final double FADE_SECONDS = 0.008;

    /** The open line, or {@code null} while idle. Guarded by {@link #LOCK}. */
    private static SourceDataLine line;
    private static long lastUsedAt;

    /** One writer at a time; the playback thread and the idle reaper both touch the line. */
    private static final Object LOCK = new Object();

    /** True once this session has given up on the independent output entirely. */
    private static volatile boolean unavailable;

    /** So the "falling back to the game's sound" explanation is logged once, not once per alert. */
    private static final AtomicBoolean loggedFallback = new AtomicBoolean();

    /** A ping is played off-thread; this flag keeps a burst from stacking playback threads. */
    private static final AtomicBoolean playing = new AtomicBoolean();

    private SbsAudio() {
    }

    /** The tones the mod can raise; each alert picks one. */
    public enum Tone {
        /** Two rising notes - "something happened you asked to know about". */
        CHIME(new double[] {880.0, 1320.0}, new int[] {110, 150}),
        /** A single low note - "heads up", deliberately softer than CHIME. */
        BLIP(new double[] {520.0}, new int[] {140}),
        /** Three quick rising notes - reserved for the loudest events. */
        ALARM(new double[] {700.0, 900.0, 1200.0}, new int[] {90, 90, 160});

        private final double[] frequencies;
        private final int[] durationsMs;

        Tone(double[] frequencies, int[] durationsMs) {
            this.frequencies = frequencies;
            this.durationsMs = durationsMs;
        }
    }

    /**
     * Plays {@code tone} at {@code volumePercent} of full scale, off the game thread.
     *
     * <p>Returns whether the independent output took it. On {@code false} the caller has already had
     * the vanilla fallback played for it - the return value is for diagnostics ({@code /sbs
     * testnotify}), not an instruction to retry.
     */
    public static boolean play(Tone tone, int volumePercent) {
        if (volumePercent <= 0) {
            return false;
        }
        if (unavailable) {
            return vanillaFallback();
        }
        if (!playing.compareAndSet(false, true)) {
            return true;   // a ping is already sounding; a second one on top of it is just noise
        }
        Thread thread = new Thread(() -> {
            try {
                if (!playTone(tone, volumePercent)) {
                    vanillaFallback();
                }
            } finally {
                playing.set(false);
            }
        }, "SBS-Audio");
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    /** Writes one tone to the line, reopening the device if it is idle or was lost. */
    private static boolean playTone(Tone tone, int volumePercent) {
        byte[] samples = render(tone);
        synchronized (LOCK) {
            SourceDataLine open = openLine();
            if (open == null) {
                return false;
            }
            try {
                applyGain(open, volumePercent);
                open.write(samples, 0, samples.length);
                open.drain();
                lastUsedAt = System.currentTimeMillis();
                return true;
            } catch (IllegalArgumentException | IllegalStateException e) {
                // The device went away mid-write (unplugged, Bluetooth switched, driver reset).
                // Drop the handle so the next ping opens whatever is the default device by then.
                closeLine();
                warnOnce("the audio device was lost mid-playback", e);
                return false;
            }
        }
    }

    /** The open line, opening one if needed. {@code null} when no device will take it. */
    private static SourceDataLine openLine() {
        if (line != null) {
            return line;
        }
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, FORMAT);
        if (!AudioSystem.isLineSupported(info)) {
            unavailable = true;
            warnOnce("no audio device accepts the alert format", null);
            return null;
        }
        try {
            SourceDataLine opened = (SourceDataLine) AudioSystem.getLine(info);
            opened.open(FORMAT);
            opened.start();
            line = opened;
            return opened;
        } catch (LineUnavailableException e) {
            // Another application holds the device in exclusive mode, or there is none. Not fatal
            // and not permanent - the next ping tries again, since exclusive mode is usually a
            // passing state.
            warnOnce("the audio device is unavailable (another application may hold it)", e);
            return null;
        } catch (SecurityException | IllegalArgumentException e) {
            unavailable = true;
            warnOnce("the independent audio output cannot be used on this system", e);
            return null;
        }
    }

    /**
     * Sets the line's gain for this ping.
     *
     * <p>The control is in <b>decibels</b>, which is a logarithmic scale - assigning the percentage
     * to it directly would make "50%" a barely audible -40 dB rather than half as loud. The slider
     * is therefore treated as an amplitude ratio and converted: {@code 20·log10(fraction)}, clamped
     * into whatever range the device reports. 100% lands on 0 dB (unity), 50% on about -6 dB, which
     * is what "half volume" actually sounds like.
     */
    private static void applyGain(SourceDataLine target, int volumePercent) {
        if (!target.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            return;   // some devices have no gain control; the tone plays at its rendered level
        }
        FloatControl gain = (FloatControl) target.getControl(FloatControl.Type.MASTER_GAIN);
        double fraction = Math.max(1, Math.min(100, volumePercent)) / 100.0;
        float decibels = (float) (20.0 * Math.log10(fraction));
        gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), decibels)));
    }

    /** Releases the device when it has been idle a while. Called from the client tick. */
    public static void tick() {
        if (line == null) {
            return;
        }
        synchronized (LOCK) {
            if (line != null && System.currentTimeMillis() - lastUsedAt > IDLE_RELEASE_MS) {
                closeLine();
            }
        }
    }

    /** Drops the line. Caller holds {@link #LOCK}. */
    private static void closeLine() {
        if (line == null) {
            return;
        }
        try {
            line.stop();
            line.close();
        } catch (RuntimeException ignored) {
            // Closing a device that is already gone throws; there is nothing left to clean up.
        }
        line = null;
    }

    /** The tone as raw PCM: each note faded in and out so the chime has no clicks. */
    private static byte[] render(Tone tone) {
        int total = 0;
        for (int ms : tone.durationsMs) {
            total += (int) (SAMPLE_RATE * ms / 1000f);
        }
        byte[] out = new byte[total * 2];
        int index = 0;
        for (int note = 0; note < tone.frequencies.length; note++) {
            int samples = (int) (SAMPLE_RATE * tone.durationsMs[note] / 1000f);
            double frequency = tone.frequencies[note];
            double fade = SAMPLE_RATE * FADE_SECONDS;
            for (int i = 0; i < samples; i++) {
                double envelope = Math.min(1.0, Math.min(i, samples - i) / fade);
                short value = (short) (Math.sin(2 * Math.PI * frequency * i / SAMPLE_RATE)
                        * AMPLITUDE * envelope);
                out[index++] = (byte) (value & 0xFF);
                out[index++] = (byte) ((value >> 8) & 0xFF);
            }
        }
        return out;
    }

    /**
     * The last resort when the independent output cannot play: Minecraft's own ping. It obeys the
     * game's volume sliders - which is the whole thing this class exists to avoid - but a quiet
     * alert beats no alert, and the log says which one the player got.
     */
    private static boolean vanillaFallback() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return false;
        }
        minecraft.execute(() -> {
            if (minecraft.player != null) {
                minecraft.player.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 1.0f, 1.2f);
            }
        });
        if (loggedFallback.compareAndSet(false, true)) {
            SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][Audio] alert pings are using the game's sound engine, so Minecraft's "
                            + "volume sliders apply to them.");
        }
        return false;
    }

    /** Whether the independent output is believed to work - drives the settings label. */
    public static String statusText() {
        if (unavailable) {
            return "unavailable - using the game's sound instead";
        }
        return line != null ? "ready (device open)" : "ready";
    }

    private static void warnOnce(String reason, Throwable cause) {
        if (loggedFallback.compareAndSet(false, true)) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Audio] {} - alert pings fall back to the "
                    + "game's sound engine.", reason, cause);
        }
    }
}
