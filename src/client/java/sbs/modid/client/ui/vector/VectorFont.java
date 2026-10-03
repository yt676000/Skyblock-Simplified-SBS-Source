/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.vector;

import java.util.HashMap;
import java.util.Map;

/**
 * The monoline geometric capital alphabet the SBS wordmark is set in.
 *
 * <p>Glyphs are stored as <em>centre lines</em>, not as filled outlines: this is a single-weight
 * face – every stroke has the same thickness, circles are circles – so a stroked skeleton is both
 * far less data than an outline and lets the weight be tuned with one number. Only the letters the
 * wordmark uses are defined; {@link #glyph} returns {@code null} for anything else and the caller
 * simply advances past it.
 *
 * <p>Design box: {@value #GLYPH_WIDTH} wide, cap height {@value #CAP_HEIGHT}, y growing downwards
 * with the cap line at 0 and the baseline at 100. Letters that read narrow (I) still occupy the
 * full box – the wordmark is tracked out so wide that per-letter widths would be invisible.
 */
public final class VectorFont {

    /** Width of the design box every glyph is drawn in. */
    public static final float GLYPH_WIDTH = 70F;
    /** Height of the design box: cap line to baseline. */
    public static final float CAP_HEIGHT = 100F;

    private static final Map<Character, SvgPath> GLYPHS = new HashMap<>();

    private VectorFont() {
    }

    static {
        // Straight-sided letters.
        define('I', "M35 0 L35 100");
        define('L', "M12 0 L12 100 L60 100");
        define('E', "M62 0 L12 0 L12 100 L62 100 M12 50 L52 50");
        define('F', "M12 100 L12 0 L62 0 M12 48 L52 48");
        define('M', "M6 100 L6 0 L35 62 L64 0 L64 100");
        define('K', "M10 0 L10 100 M62 0 L12 52 L62 100");
        define('Y', "M8 0 L35 54 L62 0 M35 54 L35 100");
        // Round letters. The bowls are quarter-circle cubics (k = 0.5523) so they stay truly circular.
        define('O', "M35 2 C52 2 66 23 66 50 C66 77 52 98 35 98 "
                + "C18 98 4 77 4 50 C4 23 18 2 35 2 Z");
        define('C', "M63 22 C57 10 47 2 35 2 C18 2 4 23 4 50 C4 77 18 98 35 98 C47 98 57 90 63 78");
        define('S', "M62 21 C62 9 50 2 36 2 C21 2 9 10 9 24 C9 38 22 43 36 47 "
                + "C51 51 63 57 63 73 C63 89 50 98 35 98 C20 98 8 90 8 77");
        define('D', "M10 2 L32 2 C55 2 66 20 66 50 C66 80 55 98 32 98 L10 98 Z");
        define('P', "M10 100 L10 2 L36 2 C53 2 63 12 63 27 C63 42 53 52 36 52 L10 52");
        define('B', "M10 2 L10 98 M10 2 L34 2 C49 2 58 11 58 26 C58 41 49 50 34 50 L10 50 "
                + "M10 50 L37 50 C53 50 62 59 62 74 C62 89 53 98 37 98 L10 98");
    }

    private static void define(char letter, String pathData) {
        GLYPHS.put(letter, SvgPath.of(pathData));
    }

    /** The centre-line path of one capital, or {@code null} when the letter is not in the face. */
    public static SvgPath glyph(char letter) {
        return GLYPHS.get(Character.toUpperCase(letter));
    }

    /**
     * Sets a word on {@code canvas}, left edge at {@code x} and cap line at {@code y}, scaled to
     * {@code capHeight} and tracked out by {@code tracking} viewBox units between glyph boxes.
     *
     * @return the width the word occupies, so the caller can centre it
     */
    public static float draw(VectorCanvas canvas, String text, float x, float y,
                             float capHeight, float tracking, float strokeWidth, VectorPaint paint) {
        float unit = capHeight / CAP_HEIGHT;
        float step = GLYPH_WIDTH * unit + tracking;
        float cursor = x;
        for (int i = 0; i < text.length(); i++) {
            SvgPath glyph = glyph(text.charAt(i));
            if (glyph != null) {
                canvas.push();
                canvas.translate(cursor, y);
                canvas.scale(unit, unit);
                // The stroke width is given in wordmark units, so undo the glyph scale on it.
                canvas.stroke(glyph, strokeWidth / unit, paint);
                canvas.pop();
            }
            cursor += step;
        }
        return width(text, capHeight, tracking);
    }

    /** Width of a word set with {@link #draw}, ignoring the trailing tracking. */
    public static float width(String text, float capHeight, float tracking) {
        if (text.isEmpty()) {
            return 0F;
        }
        float unit = capHeight / CAP_HEIGHT;
        return text.length() * (GLYPH_WIDTH * unit + tracking) - tracking;
    }
}
