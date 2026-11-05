/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.puzzle.logic;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads Oruo's question frame out of chat, one colour-stripped line at a time. No Minecraft types,
 * unit-tested on frames captured from real runs.
 *
 * <p><b>The frame, as logged in 1,161 Quiz rooms:</b>
 * <pre>
 *                                 Question #1
 *            What is the name of the vendor in the Hub who sells stained
 *                                     glass?
 *
 *     ? Wool Weaver
 *     ? Builder
 *     ? Adventurer
 * </pre>
 * A centred {@code Question #N}, the centred question - which Hypixel wraps, so a question line that
 * does not end in {@code ?} continues on the next centred line - sometimes a blank line, then three
 * indented option lines, each a glyph, a space and the option text. The 1.8.9 log prints the glyph
 * as {@code ?}; the parser accepts any one or two non-alphanumeric characters there.
 *
 * <p>Other chat can land between those lines (a teammate's loot message), so lines that are not
 * indented are skipped rather than ending the frame. A line can also carry a chat-stacking counter,
 * {@code " (2)"}, when the same text arrived twice; it is cut off before anything is compared.
 *
 * <p>A frame that has not completed within {@link #MAX_LINES} lines is dropped: a half-read frame
 * yields no question, which is the safe outcome.
 */
public final class QuizFrame {

    /** A frame is a handful of lines; anything longer means its tail was never seen. */
    static final int MAX_LINES = 16;

    private static final Pattern HEADER = Pattern.compile("^Question #(\\d+)$");
    private static final Pattern STACK_SUFFIX = Pattern.compile("\\s+\\(\\d+\\)$");
    /** Indent, a one- or two-char glyph with no letter or digit, a space, the option text. */
    private static final Pattern OPTION = Pattern.compile("^\\s{2,}([^\\sA-Za-z0-9]{1,2})\\s+(\\S.*)$");

    /** One complete question as Oruo asked it. */
    public record Question(int number, String text, List<String> options) {
    }

    private enum Stage { IDLE, QUESTION, CONTINUATION, OPTIONS }

    private Stage stage = Stage.IDLE;
    private int number;
    private final StringBuilder text = new StringBuilder();
    private final List<String> options = new ArrayList<>(3);
    private int linesInFrame;

    /**
     * Offers one colour-stripped chat line.
     *
     * @return the question once its third option has arrived, else {@code null}
     */
    public Question offer(String line) {
        if (line == null) {
            return null;
        }
        String clean = STACK_SUFFIX.matcher(line).replaceFirst("");
        Matcher header = HEADER.matcher(clean.trim());
        if (header.matches()) {
            // A new header always restarts, so a frame abandoned half-way cannot bleed into the next.
            clear();
            stage = Stage.QUESTION;
            number = Integer.parseInt(header.group(1));
            return null;
        }
        if (stage == Stage.IDLE) {
            return null;
        }
        if (++linesInFrame > MAX_LINES) {
            clear();
            return null;
        }
        String trimmed = clean.trim();
        boolean indented = !clean.isEmpty() && Character.isWhitespace(clean.charAt(0));
        if (trimmed.isEmpty() || !indented) {
            return null;
        }
        Matcher option = OPTION.matcher(clean);
        switch (stage) {
            case QUESTION -> {
                if (option.matches()) {
                    clear();
                    return null;
                }
                text.append(trimmed);
                stage = trimmed.endsWith("?") ? Stage.OPTIONS : Stage.CONTINUATION;
            }
            case CONTINUATION -> {
                if (option.matches()) {
                    // Options before the question ended: not a shape this parser knows.
                    clear();
                    return null;
                }
                text.append(' ').append(trimmed);
                if (trimmed.endsWith("?")) {
                    stage = Stage.OPTIONS;
                }
            }
            case OPTIONS -> {
                if (!option.matches()) {
                    return null;
                }
                options.add(option.group(2).trim());
                if (options.size() == 3) {
                    Question done = new Question(number, text.toString(), List.copyOf(options));
                    clear();
                    return done;
                }
            }
            default -> {
            }
        }
        return null;
    }

    /** Back to waiting for a header. */
    public void clear() {
        stage = Stage.IDLE;
        number = 0;
        text.setLength(0);
        options.clear();
        linesInFrame = 0;
    }
}
