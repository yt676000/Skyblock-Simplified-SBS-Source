/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.level;

/**
 * One band of the SkyBlock level colouring: the level it starts at, and the colour from there on.
 *
 * <p><b>A tier is a start, never a range.</b> Each one runs until the next one begins, and the
 * highest runs forever - which is what keeps the table correct as Hypixel raises the cap, with no
 * code change and no "and everything above" special case. It also removes gaps as a concept: the
 * contributor's example ("black up to 560, another colour from 600") is ambiguous only because it
 * mixes ends with starts, and entering starts alone cannot be ambiguous.
 *
 * <p><b>{@link #PASSTHROUGH} is how a band says "leave it alone".</b> Without it there would be no
 * way to express "colour these two bands myself but let Hypixel have the rest", and the honest
 * answer for a band nobody has configured is Hypixel's own colour rather than a colour we invented.
 * It is also what an unreadable entry degrades to.
 *
 * <p>A plain mutable POJO because Gson (de)serialises it straight out of the config, exactly like
 * {@code Waypoint} and {@code TextReplacement}.
 */
public final class LevelTier {

    /** {@link #hex} value meaning "do not recolour this band - draw Hypixel's own colour". */
    public static final String PASSTHROUGH = "";

    /** The lowest level this tier applies to, inclusive. */
    public int from;

    /** {@code RRGGBB}, or {@link #PASSTHROUGH}. Stored as hex to match every other SBS colour. */
    public String hex = PASSTHROUGH;

    /** Gson needs a no-arg constructor. */
    public LevelTier() {
    }

    public LevelTier(int from, String hex) {
        this.from = from;
        this.hex = hex == null ? PASSTHROUGH : hex;
    }

    /** Whether this band deliberately keeps the server's colour. */
    public boolean isPassthrough() {
        return hex == null || hex.isBlank();
    }

    /** A copy, so the live config is never handed out to something that might mutate it. */
    public LevelTier copy() {
        return new LevelTier(from, hex);
    }
}
