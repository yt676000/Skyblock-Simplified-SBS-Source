/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.foraging.beacon.model;

import sbs.modid.client.core.sound.NotePitch;

import java.util.Locale;

/**
 * What the client can currently say about the beacon's beat: one immutable answer per property, each
 * carrying <b>how well it knows it</b>.
 *
 * <p>The confidence is not decoration. The request's rule is that a property which cannot be read
 * confidently shows nothing at all, and the only way to honour that is for "I do not know yet" and
 * "I cannot know" to be different values that survive all the way to the renderer. A record of bare
 * numbers would force the renderer to invent the distinction from zeroes.
 *
 * @param soundId    the repeating sound the beat was read from, or {@code null} while none is found
 * @param note       the note-block note the pitch encodes, or {@code null} when it is not on the grid
 * @param pitch      the raw pitch last heard, meaningful only with {@code pitchState != UNKNOWN}
 * @param pitchState what is known about the pitch — see {@link Confidence}
 * @param intervalMs the median gap between beats, or {@code 0} when unknown
 * @param speedState what is known about the interval
 * @param beats      how many beats this reading was built from
 * @param lastBeatAt when the most recent beat arrived, epoch millis; {@code 0} for never
 */
public record BeatReading(String soundId, Integer note, float pitch, Confidence pitchState,
                          long intervalMs, Confidence speedState, int beats, long lastBeatAt) {

    /** Nothing heard yet. */
    public static final BeatReading NONE =
            new BeatReading(null, null, 0f, Confidence.UNKNOWN, 0, Confidence.UNKNOWN, 0, 0);

    /**
     * How well a property is known.
     *
     * <p>{@link #FIXED} is the one that earns its place. A pitch that never changes is ambiguous in a
     * way that matters: Minecraft's note grid includes {@code 1.0} (F#4), so a resource-pack sample
     * played at the default pitch is <i>indistinguishable from a genuinely tuned F#4</i> on any single
     * beat. Only variation over time separates them. Reporting a constant pitch as a confident note
     * would be the single most likely way for this feature to be confidently wrong.
     */
    public enum Confidence {
        /** Not enough heard yet. Show nothing. */
        UNKNOWN,
        /** Measured from several beats and consistent. Show it. */
        MEASURED,
        /** Read, but constant for the whole session — cannot be told from a fixed cue. Show it as such. */
        FIXED,
        /** Structurally unreadable: the value exists but carries no usable meaning. */
        UNREADABLE
    }

    /** True when anything at all has been heard. */
    public boolean heard() {
        return soundId != null && beats > 0;
    }

    /** The pitch line for a display, or {@code null} when the rule says to show nothing. */
    public String pitchLine() {
        return switch (pitchState) {
            case UNKNOWN -> null;
            case MEASURED -> note != null ? NotePitch.name(note)
                    : String.format(Locale.ROOT, "%.4f (not a note)", pitch);
            case FIXED -> (note != null ? NotePitch.name(note)
                    : String.format(Locale.ROOT, "%.4f", pitch)) + " (never varies)";
            case UNREADABLE -> null;
        };
    }

    /** The speed line for a display, or {@code null} when it is not known well enough to show. */
    public String speedLine() {
        if (speedState != Confidence.MEASURED || intervalMs <= 0) {
            return null;
        }
        return String.format(Locale.ROOT, "%.2fs between beats", intervalMs / 1000.0);
    }
}
