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
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Rewriting the <i>visible characters</i> of a component without losing how it looked.
 *
 * <p><b>Why this exists.</b> The obvious way to change displayed text - {@code getString()}, edit the
 * string, wrap it back up in a {@code Component.literal} - throws away everything that is not
 * characters. A component's colour usually lives in its {@link Style} rather than as a {@code §} code
 * in its text, and so do its hover and click events, so the rebuilt line renders white and stops
 * being clickable. Every feature that edits shown text hits this, and there is exactly one correct
 * answer, so it lives here rather than once per feature.
 *
 * <p><b>How.</b> {@link Flat#of(Component)} reduces a component to its visible characters, each
 * remembering the style it inherited and the legacy {@code §} codes in force at that point. Matching
 * then runs on {@link Flat#plain()} - plain text, no formatting in the way, which is what lets a
 * match run straight through a colour change ("Ender §6Dragon" matches "Ender Dragon"). {@link
 * Flat#rebuild} puts the formatting back around whatever survived, and hands each replacement the
 * style and codes in force at the first character it replaced.
 *
 * <p>Callers decide <i>what</i> to match - a literal, a regex, a name from a roster - and hand back
 * {@link Span}s over the plain text. This class only knows how to splice them in without breaking
 * the formatting.
 */
public final class StyledText {

    private static final char LEGACY = '§';

    private StyledText() {
    }

    /**
     * One replacement over {@link Flat#plain()}: characters {@code [start, end)} become
     * {@code replacement}. An empty replacement deletes the span.
     *
     * <p>Half-open like {@code substring}, so {@code end - start} is the length being removed and a
     * {@link java.util.regex.Matcher}'s {@code start()} / {@code end()} can be passed straight in.
     */
    public record Span(int start, int end, String replacement) {

        public Span {
            if (start < 0 || end < start) {
                throw new IllegalArgumentException("bad span " + start + ".." + end);
            }
            replacement = replacement == null ? "" : replacement;
        }
    }

    /**
     * A component decomposed into visible characters plus the formatting each one carried.
     *
     * <p>Cheap to build and meant to be thrown away: one per component being rewritten. Nothing here
     * is shared or cached, so it is safe to build from any thread that owns its component.
     */
    public static final class Flat {

        private final StringBuilder plain = new StringBuilder();
        private final List<Style> styles = new ArrayList<>();
        private final List<String> legacy = new ArrayList<>();

        private Flat() {
        }

        /** The component flattened; never {@code null}, empty for a component with no text. */
        public static Flat of(Component text) {
            Flat flat = new Flat();
            if (text == null) {
                return flat;
            }
            text.visit((style, string) -> {
                // Legacy codes only carry within one component's own content, so the active set
                // starts empty for every run rather than leaking in from the previous one.
                StringBuilder active = new StringBuilder(4);
                String snapshot = "";
                for (int i = 0; i < string.length(); i++) {
                    char c = string.charAt(i);
                    if (c == LEGACY && i + 1 < string.length()) {
                        char code = string.charAt(++i);
                        if (resets(code)) {
                            active.setLength(0);
                        }
                        active.append(LEGACY).append(code);
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

        /** The visible text with every formatting code removed - what callers match against. */
        public String plain() {
            return plain.toString();
        }

        /** How many visible characters there are; the exclusive upper bound for a {@link Span}. */
        public int length() {
            return plain.length();
        }

        /** Every start index of {@code needle} in the visible text, left to right, non-overlapping. */
        public List<Span> find(String needle, String replacement) {
            List<Span> found = new ArrayList<>();
            if (needle == null || needle.isEmpty()) {
                return found;
            }
            String text = plain.toString();
            int at = text.indexOf(needle);
            while (at >= 0) {
                found.add(new Span(at, at + needle.length(), replacement));
                at = text.indexOf(needle, at + needle.length());
            }
            return found;
        }

        /**
         * The component again with {@code spans} spliced in. {@code spans} must be sorted by
         * {@code start} and must not overlap - which is what both a left-to-right literal scan and a
         * {@link java.util.regex.Matcher} loop produce anyway.
         *
         * <p>Characters are emitted in runs that share a style and a legacy state, so the output has
         * one sibling per stretch that actually looked the same, not one per character. A replacement
         * takes the style and codes of the first character it covers: of the styles a match spanned
         * that is the only defensible choice, and it is what makes "Ender Dragon" → "ED" keep the
         * dragon's colour.
         */
        public Component rebuild(List<Span> spans) {
            MutableComponent out = Component.empty();
            StringBuilder buffer = new StringBuilder();
            Style bufferStyle = null;
            String bufferLegacy = "";
            int next = 0;
            int i = 0;
            while (i < plain.length()) {
                if (next < spans.size() && spans.get(next).start() == i) {
                    Span span = spans.get(next);
                    flush(out, buffer, bufferStyle, bufferLegacy);
                    bufferStyle = null;
                    if (!span.replacement().isEmpty()) {
                        out.append(Component.literal(legacy.get(i) + span.replacement())
                                .setStyle(styles.get(i)));
                    }
                    // A span may not run past the end - clamping keeps a caller's arithmetic slip
                    // from becoming an index crash inside a render pass.
                    i = Math.min(plain.length(), Math.max(i + 1, span.end()));
                    next++;
                    continue;
                }
                Style style = styles.get(i);
                String active = legacy.get(i);
                if (bufferStyle == null) {
                    bufferStyle = style;
                    bufferLegacy = active;
                } else if (!style.equals(bufferStyle) || !active.equals(bufferLegacy)) {
                    flush(out, buffer, bufferStyle, bufferLegacy);
                    bufferStyle = style;
                    bufferLegacy = active;
                }
                buffer.append(plain.charAt(i));
                i++;
            }
            flush(out, buffer, bufferStyle, bufferLegacy);
            return out;
        }

        private static void flush(MutableComponent out, StringBuilder buffer, Style style, String legacy) {
            if (buffer.isEmpty()) {
                return;
            }
            out.append(Component.literal(legacy + buffer)
                    .setStyle(style == null ? Style.EMPTY : style));
            buffer.setLength(0);
        }

        /** Whether a legacy code clears the codes before it (colours and {@code §r} do, §k-§o do not). */
        private static boolean resets(char code) {
            char c = Character.toLowerCase(code);
            return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || c == 'r';
        }
    }

    /**
     * A {@link FormattedCharSequence} walked back into a component - the shape the tab list and
     * several vanilla widgets hand their text to the renderer in.
     *
     * <p>A sequence is already decomposed into styled code points, so this is only regrouping them
     * into runs. Worth doing because it is the difference between an edit reaching the tab list and
     * not; {@link Component} is the cheaper path wherever one is available.
     */
    public static Component toComponent(FormattedCharSequence sequence) {
        MutableComponent rebuilt = Component.empty();
        StringBuilder run = new StringBuilder();
        Style[] runStyle = {null};
        sequence.accept((index, style, codePoint) -> {
            if (runStyle[0] != null && !runStyle[0].equals(style)) {
                rebuilt.append(Component.literal(run.toString()).setStyle(runStyle[0]));
                run.setLength(0);
            }
            runStyle[0] = style;
            run.appendCodePoint(codePoint);
            return true;
        });
        if (!run.isEmpty()) {
            rebuilt.append(Component.literal(run.toString())
                    .setStyle(runStyle[0] == null ? Style.EMPTY : runStyle[0]));
        }
        return rebuilt;
    }

    /**
     * {@code text} with every {@code §} code removed. The cheap form, for a caller that only needs to
     * ask "could this string contain X" before paying for a full {@link Flat}.
     */
    public static String strip(String text) {
        if (text == null || text.indexOf(LEGACY) < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == LEGACY && i + 1 < text.length()) {
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }
}
