/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.theme;

import java.util.function.ToIntFunction;

/**
 * One "player's hex override, else the theme's colour" slot, resolved once and then read back.
 *
 * <p>HUD cards and bars ask for their colours every frame, and the answer only changes when the
 * player edits the field or the theme is re-derived. Parsing the text on every ask was the cost;
 * this keeps the last answer and re-parses only when one of its inputs moved:
 * <ul>
 *   <li>the override string - compared by reference, which is exact because config strings are
 *       immutable and an edit always stores a new one;</li>
 *   <li>the theme colour it falls back to, and {@link SBSTheme#revision()} - bumped by
 *       {@link SBSTheme#applyTheme}, the one point every theme, style and opacity change passes.</li>
 * </ul>
 * So a half-typed field still resolves live, with no listener anyone has to remember to call.
 *
 * <p>Render thread only, like everything that draws.
 */
public final class ThemedHex {

    /** Returned by a parser for text that is not a colour (yet). */
    public static final int INVALID = -1;

    private final ToIntFunction<String> parser;
    private String lastHex;
    private int lastFallback;
    private int lastRevision = -1;
    private int value;

    /** @param parser {@code RRGGBB} text to a 24-bit RGB, or {@link #INVALID} */
    public ThemedHex(ToIntFunction<String> parser) {
        this.parser = parser;
    }

    /**
     * {@code hex} over {@code fallback}'s alpha, or {@code fallback} itself when {@code hex} is blank
     * or unreadable. The alpha is the theme's on purpose: the picker speaks RGB only.
     */
    public int resolve(String hex, int fallback) {
        int revision = SBSTheme.revision();
        if (hex != lastHex || fallback != lastFallback || revision != lastRevision) {
            int rgb = hex == null || hex.isBlank() ? INVALID : parser.applyAsInt(hex);
            value = rgb == INVALID ? fallback : (fallback & 0xFF000000) | (rgb & 0xFFFFFF);
            lastHex = hex;
            lastFallback = fallback;
            lastRevision = revision;
        }
        return value;
    }
}
