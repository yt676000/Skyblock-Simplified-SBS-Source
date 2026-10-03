/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.sound;

/**
 * The note-block pitch grid: turns the {@code float} pitch a sound carries into the note it encodes,
 * and back.
 *
 * <p>Minecraft plays note-block notes by pitch alone — {@code pitch = 2^((note − 12) / 12)} over the
 * 25 notes 0..24, which is F♯3 to F♯5. The mapping is exact and reversible, so a pitch value read off
 * a sound is not an approximation of a note: it <i>is</i> the note, and
 * {@code note = round(12·log₂(pitch) + 12)} recovers it losslessly.
 *
 * <p><b>Why that matters.</b> A feature that has to tell a player which pitch to match can either
 * show them {@code 1.059463} or show them {@code G3}. Only one of those is usable while playing, and
 * turning a musical value into a musical name is the whole difference between reading a number and
 * answering the question.
 *
 * <p><b>{@link #noteOf} returns {@code null} rather than a nearest note</b>, and that is the point of
 * the class. A pitch that is not on the grid did not come from a note block, and rounding it to the
 * closest note would invent a musical reading for a sound that has none — which is exactly how a
 * "pitch solver" ends up confidently wrong about a pre-pitched sample. Off the grid, the honest
 * answer is that there is no note here.
 */
public final class NotePitch {

    /** The lowest note-block note. */
    public static final int MIN_NOTE = 0;

    /** The highest note-block note; 25 notes inclusive of {@link #MIN_NOTE}. */
    public static final int MAX_NOTE = 24;

    /**
     * How far off the exact grid a pitch may sit and still be read as that note.
     *
     * <p>Generous enough to absorb {@code float} rounding through the network codec, tight enough
     * that an arbitrary pitch (a sample played at 0.8, say) is refused rather than snapped: the
     * neighbouring grid points are ~6% apart, so this is roughly a fortieth of that spacing.
     */
    private static final double TOLERANCE = 0.0015;

    /** Note names, index 0..24 — F♯3 up to F♯5, the note block's whole range. */
    private static final String[] NAMES = {
        "F#3", "G3", "G#3", "A3", "A#3", "B3", "C4", "C#4", "D4", "D#4", "E4", "F4",
        "F#4", "G4", "G#4", "A4", "A#4", "B4", "C5", "C#5", "D5", "D#5", "E5", "F5", "F#5",
    };

    private NotePitch() {
    }

    /** The exact pitch a note block plays for {@code note}. */
    public static float pitchOf(int note) {
        return (float) Math.pow(2.0, (clamp(note) - 12) / 12.0);
    }

    /**
     * The note this pitch encodes, or {@code null} when it is not on the note-block grid at all.
     *
     * <p>Never guesses. See the class note: a nearest-note answer for an off-grid pitch is an
     * invented reading, and the caller needs to be able to tell "this is G3" from "this is not a note
     * block".
     */
    public static Integer noteOf(double pitch) {
        if (!(pitch > 0) || !Double.isFinite(pitch)) {
            return null;
        }
        long note = Math.round(12.0 * (Math.log(pitch) / Math.log(2.0)) + 12.0);
        if (note < MIN_NOTE || note > MAX_NOTE) {
            return null;
        }
        return Math.abs(pitchOf((int) note) - pitch) <= TOLERANCE ? (int) note : null;
    }

    /** The note's name ({@code "G#4"}), or {@code "?"} for a note outside the grid. */
    public static String name(int note) {
        return note < MIN_NOTE || note > MAX_NOTE ? "?" : NAMES[note];
    }

    /**
     * The note name for a pitch, or {@code null} when it is not a note-block pitch. The convenience
     * form of {@link #noteOf} for display code that has nothing to do with the number itself.
     */
    public static String nameOf(double pitch) {
        Integer note = noteOf(pitch);
        return note == null ? null : name(note);
    }

    private static int clamp(int note) {
        return Math.max(MIN_NOTE, Math.min(MAX_NOTE, note));
    }
}
