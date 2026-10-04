/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.util;

/**
 * Finding the figures in a line of text - the half of number rewriting that has nothing to do with
 * what the figures then become.
 *
 * <p>This was private to {@link NumberTextFormat}, which needed it to shorten the server's numbers.
 * {@link SpokenNumbers} needs exactly the same reading of a line for a completely different output,
 * and a second copy of these rules would be the worst of both: subtle, identical, and free to drift.
 * The rules live here once and each caller says only what a figure turns into.
 *
 * <h2>What counts as a number</h2>
 * A run of digits, the {@code ,} and {@code .} between them, and a leading {@code -} when it is a
 * sign rather than a hyphen. Three rules keep this off everything that merely looks like a value,
 * and they are the reason a scan is needed at all rather than a regular expression:
 * <ul>
 *   <li>a digit with a letter or digit immediately before it belongs to a word, not to a value -
 *       {@code mini123AB} and {@code b3} are read as text;</li>
 *   <li>a run with a letter immediately after it is likewise part of a word, and is reported with a
 *       {@code null} replacement whatever the caller would have said;</li>
 *   <li>{@code §} colour codes are stepped over as pairs, so the digit in {@code §4} is never read
 *       as part of a number, and the code does not count as "what came before" either - which is
 *       what lets {@code Purse: §61,234} still read as one value.</li>
 * </ul>
 *
 * <p>The {@code -} follows from the same idea. It joins the number only where a hyphen could not be:
 * {@code -3} and {@code (-1,234)} are signed values, while {@code Level-3} and {@code 2024-01-15}
 * have a letter or digit in front of the dash and keep it as punctuation. Digits on either side of
 * such a dash are left verbatim altogether: {@code 2024-01-15} is a date, never {@code 2,024-01-15}.
 *
 * <p>Trailing separators are punctuation, not part of the figure - {@code 1,000.} at the end of a
 * sentence gives up its full stop before the caller ever sees it.
 */
public final class NumberScan {

    private static final char SECTION_SIGN = (char) 0x00A7;

    private NumberScan() {
    }

    /** Decides what one figure becomes. */
    @FunctionalInterface
    public interface Rewriter {

        /**
         * @param token     the figure as written, sign and separators included ({@code -1,234.5})
         * @param preceding the character before it, or {@code 0} at the start of the text
         * @param following the character after it, or {@code 0} at the end of the text
         * @return what to say instead, or {@code null} to leave the figure exactly as written
         */
        String rewrite(String token, char preceding, char following);
    }

    /**
     * Receives the text in order, in one pass: the stretches between figures and the figures
     * themselves, as index ranges into the original string.
     *
     * <p>Ranges rather than substrings because the callers that need this rather than
     * {@link #rewrite} need them - {@link NumberTextFormat#apply} carries a style and a legacy
     * colour state per character, and it can only keep those aligned if it is told where in the
     * original each piece came from.
     */
    public interface Sink {

        /** A stretch of {@code [start, end)} that is not part of any figure. May be empty. */
        void literal(int start, int end);

        /**
         * A figure occupying {@code [start, end)}.
         *
         * @param replacement what to write instead, or {@code null} to copy the figure verbatim
         */
        void number(int start, int end, String replacement);
    }

    /**
     * Every figure in {@code text} passed through {@code rewriter}.
     *
     * @return the rewritten text, or the input itself when nothing changed - so a caller can skip
     *         re-deriving whatever it derives from the text
     */
    public static String rewrite(String text, Rewriter rewriter) {
        if (text == null || !hasDigit(text)) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        split(text, rewriter, new Sink() {
            @Override
            public void literal(int start, int end) {
                out.append(text, start, end);
            }

            @Override
            public void number(int start, int end, String replacement) {
                if (replacement == null) {
                    out.append(text, start, end);
                } else {
                    out.append(replacement);
                }
            }
        });
        String result = out.toString();
        return result.contentEquals(text) ? text : result;
    }

    /** Walks {@code text} once, reporting every literal stretch and every figure to {@code sink}. */
    public static void split(String text, Rewriter rewriter, Sink sink) {
        if (text == null) {
            return;
        }
        int literalFrom = 0;
        char previous = 0;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == SECTION_SIGN && i + 1 < text.length()) {
                // A colour code is not a character of the line: it is stepped over and left out of
                // the "what came before this number" test, so §61,234 still reads as a value.
                i += 2;
                continue;
            }
            boolean signed = c == '-' && i + 1 < text.length() && isDigit(text.charAt(i + 1))
                    && !Character.isLetterOrDigit(previous);
            if (!signed && (!isDigit(c) || Character.isLetterOrDigit(previous))) {
                previous = c;
                i++;
                continue;
            }
            int end = signed ? i + 1 : i;
            while (end < text.length() && isNumberChar(text.charAt(end))) {
                end++;
            }
            // "1,000." at the end of a sentence: the trailing separators are punctuation.
            while (!isDigit(text.charAt(end - 1))) {
                end--;
            }
            sink.literal(literalFrom, i);
            String token = text.substring(i, end);
            char preceding = i == 0 ? 0 : text.charAt(i - 1);
            char following = end < text.length() ? text.charAt(end) : 0;
            sink.number(i, end, Character.isLetter(following) || hyphenJoined(text, i, end)
                    ? null : rewriter.rewrite(token, preceding, following));
            literalFrom = end;
            previous = text.charAt(end - 1);
            i = end;
        }
        sink.literal(literalFrom, text.length());
    }

    /**
     * Whether the figure at {@code [start, end)} is one part of a hyphen-joined run of digits -
     * {@code 2026-10-04}, a folder stamp, a server id. Such a run is a name, not a value: grouping
     * its parts printed a session folder as {@code 2,026-10-04}.
     */
    static boolean hyphenJoined(String text, int start, int end) {
        boolean after = end + 1 < text.length() && text.charAt(end) == '-' && isDigit(text.charAt(end + 1));
        boolean before = start >= 2 && text.charAt(start - 1) == '-' && isDigit(text.charAt(start - 2));
        return after || before;
    }

    /** Whether {@code text} has any digit at all - the cheap way out of an untouched line. */
    public static boolean hasDigit(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (isDigit(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isNumberChar(char c) {
        return isDigit(c) || c == ',' || c == '.';
    }
}
