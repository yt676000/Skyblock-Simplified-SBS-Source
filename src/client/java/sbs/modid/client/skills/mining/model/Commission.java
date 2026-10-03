/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.model;

/**
 * One commission as the tab list reports it: its name and how far along it is.
 *
 * <p>{@code percent} is the number Hypixel prints. A finished commission is published as the word
 * "DONE" rather than "100%", so {@link #done} carries that separately instead of leaning on the
 * percentage having landed exactly on 100 – a commission can read "99%" and be one ore short, and
 * treating that as finished would be worse than useless.
 */
public record Commission(String name, double percent, boolean done) {

    /** The progress to draw a bar from, 0..1, with a finished commission pinned to full. */
    public float fraction() {
        if (done) {
            return 1.0f;
        }
        return (float) Math.max(0.0, Math.min(1.0, percent / 100.0));
    }

    /** The right-hand label: {@code "DONE"} or the percentage as Hypixel gave it. */
    public String progressLabel() {
        if (done) {
            return "DONE";
        }
        return percent == Math.floor(percent)
                ? (int) percent + "%"
                : String.format(java.util.Locale.US, "%.1f%%", percent);
    }
}
