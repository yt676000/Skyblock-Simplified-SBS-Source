/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.render;

/**
 * What a mark asks of the player. Each kind has its own <b>shape</b>, not only its own colour, so
 * the language reads for players who cannot separate red from green:
 *
 * <ul>
 *   <li>{@link #DO} - filled rounded glow: act on this now (left click).</li>
 *   <li>{@link #ALT} - filled glow with a corner notch: act on this now, the other way (right click).
 *       Its count badge sits top-left, where {@link #DO}'s sits bottom-right.</li>
 *   <li>{@link #NEXT} - hollow ring: the step after this one; aim at it, never act on it yet.</li>
 *   <li>{@link #WAIT} - hollow ring with a dot: yours, but not yet (melody before the note lines up).</li>
 *   <li>{@link #NOW} - filled glow that pulses: act this instant (melody when the note lines up).</li>
 * </ul>
 */
public enum MarkKind {
    DO,
    ALT,
    NEXT,
    WAIT,
    NOW;

    /** Whether the shape is filled ("act") rather than hollow ("not yet"). */
    public boolean filled() {
        return this == DO || this == ALT || this == NOW;
    }

    /** Which corner a count badge belongs in, so ALT's differs by position as well as colour. */
    public boolean badgeTopLeft() {
        return this == ALT;
    }
}
