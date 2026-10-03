/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.util;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * How SBS re-writes the numbers in text <b>the server wrote</b> - the sidebar, chat, anywhere the
 * words are Hypixel's and only the figures are worth changing.
 *
 * <p>This is the counterpart to {@link NumberDisplay}, which formats values SBS holds as numbers.
 * Here there is no value, only a line of text with digits somewhere in it, so the work is finding
 * the figures without touching everything else that is written with digits.
 *
 * <p>{@link #SERVER} leaves every line exactly as it arrives. The other two re-write the big values
 * through {@link NumberDisplay}, so a purse reads {@code 12.7M} or {@code 12,700,000} the same way
 * it does everywhere else in SBS.
 *
 * <p><b>What is deliberately never touched.</b> A sidebar line is not a number with some text around
 * it - it is a date, a server id, a set of coordinates and a purse, all written the same way.
 * {@link NumberScan} owns the rules that keep this off anything that merely looks like a value (the
 * letter-adjacency and {@code §} colour-code rules, so {@code mini123AB} and {@code 100.8M} survive);
 * this enum adds the one rule that is about shortening specifically: anything under {@link
 * #MIN_VALUE} stays as it is, because dates ({@code 07/08/24}), clock times, percentages and
 * coordinates are all small numbers and shortening them would say nothing anyway.
 */
public enum NumberTextFormat {

    /**
     * Follow the mod-wide <b>Shorten Numbers</b> switch ({@code convenience.shortenNumbers}), so one
     * setting governs every number SBS shows - its own values and the server's text alike.
     *
     * <p>The default, and the reason it exists: with a per-surface setting of its own defaulting to
     * "leave it alone", turning Shorten Numbers on visibly did nothing to the two most number-dense
     * surfaces in the game. Three switches for one idea is not a feature.
     */
    AUTO("Auto"),

    /** Leave every value exactly as the server wrote it. */
    SERVER("Server"),

    /** {@code 12.7M} - the short form used across the rest of the mod. */
    SHORT("Shortened"),

    /** {@code 12,700,000} - the full number, grouped in thousands. */
    FULL("Full");

    /** Below this a number is a date, a coordinate or a percentage - never a value worth rewriting. */
    private static final double MIN_VALUE = 1000d;

    private static final char SECTION_SIGN = (char) 0x00A7;

    private final String displayName;

    NumberTextFormat(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public NumberTextFormat next() {
        return values()[(ordinal() + 1) % values().length];
    }

    /** The value, or {@link #AUTO} when Gson read a name this enum no longer has ({@code null}). */
    public static NumberTextFormat orServer(NumberTextFormat value) {
        return value == null ? AUTO : value;
    }

    /**
     * This mode with {@link #AUTO} resolved against the mod-wide Shorten Numbers switch - what every
     * caller should ask before formatting anything. Never returns {@code AUTO}.
     */
    public NumberTextFormat resolved() {
        if (this != AUTO) {
            return this;
        }
        return NumberDisplay.enabled() ? SHORT : FULL;
    }

    /**
     * The component with every value it carries re-written, styles intact. Returns the input itself
     * when nothing changed, so the caller can skip re-deriving anything derived from the text.
     */
    public Component apply(Component input) {
        if (this == AUTO) {
            return resolved().apply(input);
        }
        if (this == SERVER || input == null) {
            return input;
        }
        Flat flat = Flat.of(input);
        String text = flat.plain.toString();
        if (!NumberScan.hasDigit(text)) {
            return input;
        }

        MutableComponent out = Component.empty();
        Run run = new Run(out);
        boolean[] changed = {false};
        NumberScan.split(text, (token, preceding, following) -> replacementFor(token),
                new NumberScan.Sink() {
                    @Override
                    public void literal(int start, int end) {
                        for (int j = start; j < end; j++) {
                            run.add(text.charAt(j), flat.styles.get(j), flat.legacy.get(j));
                        }
                    }

                    @Override
                    public void number(int start, int end, String replacement) {
                        if (replacement == null) {
                            literal(start, end);
                            return;
                        }
                        changed[0] = true;
                        // The whole figure takes the style it started in - it is one value, and the
                        // run boundary it happened to straddle was never a boundary in the number.
                        run.flush();
                        out.append(Component.literal(flat.legacy.get(start) + replacement)
                                .setStyle(flat.styles.get(start)));
                    }
                });
        run.flush();
        return changed[0] ? out : input;
    }

    /**
     * The component reduced to its visible characters, each remembering the {@link Style} it
     * inherited and the legacy {@code §} codes in force at that point.
     *
     * <p>The scan has to run over the <b>whole line</b>, not over each styled run on its own. A
     * sidebar row is assembled as {@code team prefix + entry owner + team suffix}, and Hypixel's
     * digits straddle those joins: a purse arriving as {@code "613,285,"} and {@code "195"} was
     * tokenised twice and came out {@code "613K,195"}. Flattening first is what makes the figure one
     * number again.
     */
    private static final class Flat {

        private final StringBuilder plain = new StringBuilder();
        private final List<Style> styles = new ArrayList<>();
        private final List<String> legacy = new ArrayList<>();

        static Flat of(Component input) {
            Flat flat = new Flat();
            input.visit((style, string) -> {
                // Legacy codes only carry within one component's own content, so the active set
                // starts empty for every run rather than leaking in from the previous one.
                StringBuilder active = new StringBuilder(4);
                String snapshot = "";
                for (int i = 0; i < string.length(); i++) {
                    char c = string.charAt(i);
                    if (c == SECTION_SIGN && i + 1 < string.length()) {
                        char code = string.charAt(++i);
                        if (resets(code)) {
                            active.setLength(0);
                        }
                        active.append(SECTION_SIGN).append(code);
                        snapshot = active.toString();
                        continue;
                    }
                    flat.plain.append(c);
                    flat.styles.add(style);
                    flat.legacy.add(snapshot);
                }
                return Optional.empty();
            }, Style.EMPTY);
            return flat;
        }

        /** Whether a legacy code clears the ones before it (colours and {@code §r} do, §k-§o do not). */
        private static boolean resets(char code) {
            char c = Character.toLowerCase(code);
            return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || c == 'r';
        }
    }

    /** Accumulates characters that share a style and a legacy state, so the output is not per-char. */
    private static final class Run {

        private final MutableComponent out;
        private final StringBuilder buffer = new StringBuilder();
        private Style style;
        private String legacy = "";

        Run(MutableComponent out) {
            this.out = out;
        }

        void add(char c, Style charStyle, String charLegacy) {
            if (style == null) {
                style = charStyle;
                legacy = charLegacy;
            } else if (!charStyle.equals(style) || !charLegacy.equals(legacy)) {
                flush();
                style = charStyle;
                legacy = charLegacy;
            }
            buffer.append(c);
        }

        void flush() {
            if (!buffer.isEmpty()) {
                out.append(Component.literal(legacy + buffer)
                        .setStyle(style == null ? Style.EMPTY : style));
                buffer.setLength(0);
            }
            style = null;
        }
    }

    /** One string with its values re-written; the input itself when nothing changed. */
    public String rewrite(String text) {
        if (this == AUTO) {
            return resolved().rewrite(text);
        }
        if (this == SERVER || text == null) {
            return text;
        }
        return NumberScan.rewrite(text, (token, preceding, following) -> replacementFor(token));
    }

    /**
     * One figure re-written, or {@code null} for the ones this mode leaves alone: anything that only
     * looks like a number (an ip, a version), anything under {@link #MIN_VALUE}, and anything that
     * would come back out exactly as it went in.
     */
    private String replacementFor(String token) {
        double value = parse(token);
        if (Double.isNaN(value) || Math.abs(value) < MIN_VALUE) {
            return null;
        }
        String rewritten = this == SHORT ? NumberDisplay.shorten(value) : NumberDisplay.grouped(value);
        return rewritten.equals(token) ? null : rewritten;
    }

    /** The token as a number, or {@code NaN} for anything that only looks like one (an ip, a version). */
    private static double parse(String token) {
        String plain = token.replace(",", "");
        if (plain.indexOf('.') != plain.lastIndexOf('.')) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(plain);
        } catch (NumberFormatException notANumber) {
            return Double.NaN;
        }
    }

}
