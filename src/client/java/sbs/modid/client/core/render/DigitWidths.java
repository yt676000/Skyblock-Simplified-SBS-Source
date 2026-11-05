/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.render;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.Font;
import net.minecraft.util.Mth;
import sbs.modid.SkyblockSimplifiedSBS;

import java.util.Arrays;

/**
 * Width of short number strings ({@code 12/40}, {@code 1.5M}, {@code 3:07}) without asking the font
 * every frame.
 *
 * <p>Holds the float advance of each supported character, read once per font after each font reload
 * ({@link #onFontReload}). {@code Font.width(String)} is {@code ceil} of the in-order sum of the same
 * unstyled advances, so {@link #width} returns exactly what it would. A string holding any other
 * character - a letter, a {@code §} code - falls back to {@code font.width} whole, never mixed.
 *
 * <p>For per-frame number overlays only; general text keeps measuring through the font. Client
 * thread only.
 */
public final class DigitWidths {

    private static final String SUPPORTED = "0123456789,.KMB%:/- ";

    /** Cached fonts; HUDs draw in one or two (vanilla plus an SBS wrapper), so a few slots suffice. */
    private static final int SLOTS = 4;

    private static final Font[] FONTS = new Font[SLOTS];
    private static final int[] GENERATIONS = new int[SLOTS];
    /** Advance per ASCII char; NaN marks an unsupported one. */
    private static final float[][] TABLES = new float[SLOTS][128];

    /** Bumped by {@code FontReloadMixin}; a table built under an older value is rebuilt. */
    private static volatile int generation;

    private static int nextSlot;

    private DigitWidths() {
    }

    /** Called after every font reload: glyphs, and so advances, may have changed. */
    public static void onFontReload() {
        generation++;
    }

    /** {@code font.width(text)}, without measuring when {@code text} holds only supported chars. */
    public static int width(Font font, CharSequence text) {
        float[] table = table(font);
        float sum = 0;
        for (int i = 0, n = text.length(); i < n; i++) {
            char c = text.charAt(i);
            float w = c < 128 ? table[c] : Float.NaN;
            if (w != w) {
                return font.width(text.toString());
            }
            sum += w;
        }
        return Mth.ceil(sum);
    }

    private static float[] table(Font font) {
        int gen = generation;
        for (int i = 0; i < SLOTS; i++) {
            if (FONTS[i] == font) {
                if (GENERATIONS[i] != gen) {
                    build(font, TABLES[i]);
                    GENERATIONS[i] = gen;
                }
                return TABLES[i];
            }
        }
        int slot = nextSlot;
        nextSlot = (slot + 1) % SLOTS;
        FONTS[slot] = font;
        GENERATIONS[slot] = gen;
        build(font, TABLES[slot]);
        return TABLES[slot];
    }

    private static void build(Font font, float[] table) {
        Arrays.fill(table, Float.NaN);
        for (int i = 0; i < SUPPORTED.length(); i++) {
            char c = SUPPORTED.charAt(i);
            table[c] = font.getSplitter().stringWidth(String.valueOf(c));
        }
        if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
            selfCheck(font);
        }
    }

    /** Dev runs only: the table must agree with the font for every supported char and a few mixes. */
    private static void selfCheck(Font font) {
        String[] samples = {"0", "7", "1,234,567", "12.5M", "99%", "3:07", "12/40", "-4 B", "1K", SUPPORTED};
        for (int i = 0; i < SUPPORTED.length(); i++) {
            check(font, String.valueOf(SUPPORTED.charAt(i)));
        }
        for (String s : samples) {
            check(font, s);
        }
    }

    private static void check(Font font, String s) {
        int expected = font.width(s);
        float sum = 0;
        for (int i = 0; i < s.length(); i++) {
            sum += font.getSplitter().stringWidth(String.valueOf(s.charAt(i)));
        }
        if (Mth.ceil(sum) != expected) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][Render] DigitWidths mismatch for '{}': {} vs font {}",
                    s, Mth.ceil(sum), expected);
        }
    }
}
