/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.visual.render;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/**
 * Turns a run of text into an animated chroma gradient.
 *
 * <p>Minecraft can only colour text per {@link Component}, so a gradient is necessarily built one
 * character at a time - but "per character" is a rendering constraint, not a look. What made the
 * old effect read as banding was the <i>step size</i>: a hard-coded 45° of hue per letter, i.e. a
 * full rainbow every eight characters, which is a candy stripe. Here the sweep is described in
 * {@link Params#spreadChars characters per full rainbow} instead, so a normal enchant name carries
 * roughly one smooth pass of colour and neighbouring letters differ by a few degrees.
 *
 * <p>The colours themselves come from the selected {@link ChromaPalette} via {@link Chroma#rampRgb},
 * then through {@link Chroma#evenRgb}, which holds every point of the sweep at the same perceived
 * brightness - without that the sweep visibly throbs and its dark stretch disappears into the
 * tooltip background. Text takes the constant-luminance ramp rather than the vivid one on purpose:
 * a letter has to stay legible everywhere in the sweep, and it is the letter that carries the
 * meaning, not its colour.
 *
 * <p>Time phase comes from {@link Chroma#phase}, which reduces the clock modulo the cycle in long
 * arithmetic before it ever becomes a float. Do not "simplify" that into a float division of epoch
 * millis: a 24-bit mantissa swallows the frame-to-frame delta and freezes the animation on one
 * colour, a bug this codebase has already shipped once.
 */
public final class ChromaText {

    /** Bounds of the configurable spread, in characters per full rainbow. */
    public static final int MIN_SPREAD = 4;
    public static final int MAX_SPREAD = 80;

    /**
     * How far the gradient advances per lore line when the sweep is set to flow across lines,
     * expressed as a fraction of one full rainbow. Small on purpose: consecutive enchant lines
     * should look like one continuous plate, not like a deck of unrelated rainbows.
     */
    private static final double LINE_ADVANCE_TURNS = 0.12;

    /**
     * One chroma group's look: how far it spreads and how saturated it is.
     *
     * <p><b>No speed here.</b> Spread and saturation are per-group because normal and ultimate
     * enchants are meant to be told apart at a glance; speed is not, because two sweeps running at
     * different rates in the same tooltip read as a glitch rather than as a distinction. It comes
     * from {@link Chroma#speed()}, with the rest of the mod.
     */
    public record Params(int spreadChars, int saturationPercent, boolean flowAcrossLines) {

        public int clampedSpread() {
            return Math.max(MIN_SPREAD, Math.min(MAX_SPREAD, spreadChars));
        }

        public float saturation() {
            return Math.max(0, Math.min(100, saturationPercent)) / 100.0f;
        }
    }

    private ChromaText() {
    }

    /**
     * The colour of one character of the sweep, packed RGB.
     *
     * @param charIndex position of the character within the line, in characters
     * @param lineIndex which lore line it sits on (only used when the sweep flows across lines)
     */
    public static int colorAt(int charIndex, int lineIndex, Params params) {
        double turns = charIndex / (double) params.clampedSpread();
        if (params.flowAcrossLines()) {
            turns += lineIndex * LINE_ADVANCE_TURNS;
        }
        double position = turns - Chroma.phase(Chroma.speed());
        return Chroma.evenRgb(Chroma.rampRgb(position), params.saturation());
    }

    /**
     * Appends {@code text} to {@code out}, one character per component, each keeping {@code base}'s
     * styling (bold, italic, the hover/click events Hypixel puts on lore) but carrying its own
     * gradient colour.
     *
     * @param startIndex the character index the run begins at, so a run split across styled
     *                   segments continues the same sweep instead of restarting it
     */
    public static void appendGradient(MutableComponent out, String text, Style base,
                                      int startIndex, int lineIndex, Params params) {
        for (int i = 0; i < text.length(); i++) {
            int rgb = colorAt(startIndex + i, lineIndex, params);
            out.append(Component.literal(String.valueOf(text.charAt(i)))
                    .withStyle(base.withColor(TextColor.fromRgb(rgb))));
        }
    }
}
