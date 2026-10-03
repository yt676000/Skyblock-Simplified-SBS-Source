/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.util;

import java.util.Locale;

/**
 * A tiny arithmetic evaluator for search / amount fields, with SkyBlock's coin suffixes.
 *
 * <p>Supports {@code + - * / %}, {@code ^}, parentheses, unary minus, decimals with either
 * {@code .} or {@code ,}, and the {@code k} / {@code m} / {@code b} / {@code t} multipliers used
 * everywhere in SkyBlock ({@code 5k}, {@code 1.5m}, {@code 2b} - typed in either case, the input is
 * lower-cased first). So {@code (3+2)*1.5k} is 7500 and {@code 64*1.2m} is {@code 76.8M}.
 *
 * <p>{@link #eval(String)} returns {@code null} for anything that is not a complete, valid
 * expression – that is the point: a search box calls it on <b>every</b> keystroke and must be able
 * to tell "this is maths" from "this is an item name" without throwing. A bare number is
 * deliberately <b>not</b> treated as maths (see {@link #isExpression(String)}), otherwise typing an
 * item level or an id fragment would pop a pointless "= 7".
 *
 * <p>Recursive descent, no dependencies, no state – so it is unit-testable on its own.
 */
public final class MathEval {

    /** The suffix multipliers, in the spelling players type them. */
    private static final String SUFFIXES = "kmbt";
    private static final double[] SUFFIX_VALUE = {1e3, 1e6, 1e9, 1e12};

    private final String input;
    private int pos;

    private MathEval(String input) {
        this.input = input;
    }

    /**
     * The value of {@code text}, or null when it is not a complete valid expression (which includes
     * empty input, trailing operators while still typing, and plain item names).
     */
    public static Double eval(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        MathEval parser = new MathEval(text.toLowerCase(Locale.ROOT).replace(",", "."));
        try {
            double value = parser.expression();
            parser.skipSpace();
            // Trailing junk ("5+3abc") means this was never an expression.
            if (parser.pos != parser.input.length() || !Double.isFinite(value)) {
                return null;
            }
            return value;
        } catch (ParseError notMaths) {
            return null;
        }
    }

    /**
     * Whether {@code text} is worth showing a result for: it evaluates <b>and</b> actually does
     * something – it contains an operator or a suffix, rather than being a bare number.
     */
    public static boolean isExpression(String text) {
        if (eval(text) == null) {
            return false;
        }
        String t = text.toLowerCase(Locale.ROOT);
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if ("+*/%^()".indexOf(c) >= 0 || SUFFIXES.indexOf(c) >= 0) {
                return true;
            }
            // A minus only counts as an operator when it is not the leading sign.
            if (c == '-' && i > 0) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Grammar: expression -> term (('+'|'-') term)*
    //          term       -> power (('*'|'/'|'%') power)*
    //          power      -> unary ('^' power)?      (right-associative)
    //          unary      -> ('-'|'+')? atom
    //          atom       -> number suffix? | '(' expression ')'
    // ------------------------------------------------------------------

    private double expression() {
        double value = term();
        while (true) {
            skipSpace();
            char c = peek();
            if (c == '+') {
                pos++;
                value += term();
            } else if (c == '-') {
                pos++;
                value -= term();
            } else {
                return value;
            }
        }
    }

    private double term() {
        double value = power();
        while (true) {
            skipSpace();
            char c = peek();
            if (c == '*') {
                pos++;
                value *= power();
            } else if (c == '/') {
                pos++;
                double divisor = power();
                if (divisor == 0) {
                    throw new ParseError();   // no "= Infinity" in a search box
                }
                value /= divisor;
            } else if (c == '%') {
                pos++;
                double divisor = power();
                if (divisor == 0) {
                    throw new ParseError();
                }
                value %= divisor;
            } else {
                return value;
            }
        }
    }

    private double power() {
        double base = unary();
        skipSpace();
        if (peek() == '^') {
            pos++;
            return Math.pow(base, power());
        }
        return base;
    }

    private double unary() {
        skipSpace();
        if (peek() == '-') {
            pos++;
            return -unary();
        }
        if (peek() == '+') {
            pos++;
            return unary();
        }
        return atom();
    }

    private double atom() {
        skipSpace();
        if (peek() == '(') {
            pos++;
            double value = expression();
            skipSpace();
            if (peek() != ')') {
                throw new ParseError();
            }
            pos++;
            return suffixed(value);
        }
        int start = pos;
        while (pos < input.length() && (Character.isDigit(input.charAt(pos)) || input.charAt(pos) == '.')) {
            pos++;
        }
        if (pos == start) {
            throw new ParseError();
        }
        try {
            return suffixed(Double.parseDouble(input.substring(start, pos)));
        } catch (NumberFormatException malformed) {
            throw new ParseError();
        }
    }

    /** Applies a trailing k/m/b/t multiplier, if one follows. */
    private double suffixed(double value) {
        if (pos >= input.length()) {
            return value;
        }
        int index = SUFFIXES.indexOf(input.charAt(pos));
        if (index < 0) {
            return value;
        }
        // "5km" is not a thing – a suffix must not be followed by another letter.
        if (pos + 1 < input.length() && Character.isLetter(input.charAt(pos + 1))) {
            throw new ParseError();
        }
        pos++;
        return value * SUFFIX_VALUE[index];
    }

    private void skipSpace() {
        while (pos < input.length() && input.charAt(pos) == ' ') {
            pos++;
        }
    }

    private char peek() {
        return pos < input.length() ? input.charAt(pos) : '\0';
    }

    /**
     * Formats a result the way players read coins: {@code 76.8M}, {@code 7,500}, {@code 1.25}.
     *
     * <p>Upper-case suffixes to match Hypixel and the rest of SBS
     * ({@link sbs.modid.client.core.util.NumberDisplay}), but the thresholds are the calculator's
     * own: it does not go through NumberDisplay, because a result is a number you are working with
     * and rounding it to "7.5K" loses the answer.
     */
    public static String format(double value) {
        double abs = Math.abs(value);
        if (abs >= 1e12) {
            return trim(value / 1e12) + "T";
        }
        if (abs >= 1e9) {
            return trim(value / 1e9) + "B";
        }
        if (abs >= 1e6) {
            return trim(value / 1e6) + "M";
        }
        // Below a million the exact grouped number is more useful than "7.5K".
        if (value == Math.rint(value)) {
            return String.format(Locale.ROOT, "%,d", (long) value);
        }
        return trim(value);
    }

    /**
     * The value as text that can be typed straight back in and keeps calculating with it - i.e.
     * {@code eval(toInput(v))} is {@code v} again.
     *
     * <p>Deliberately <b>not</b> {@link #format(double)}: that one groups thousands ("7,500"), and a
     * comma is a decimal point to the parser - feeding a formatted result back would silently turn
     * 7500 into 7.5. Suffixes are avoided for the same reason (they round). Plain digits only, and no
     * exponent notation, which the grammar has no rule for.
     */
    public static String toInput(double value) {
        if (!Double.isFinite(value)) {
            return "0";
        }
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return String.valueOf((long) value);
        }
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    /** Up to two decimals, without trailing zeroes. */
    private static String trim(double value) {
        String text = String.format(Locale.ROOT, "%.2f", value);
        if (text.contains(".")) {
            text = text.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return text;
    }

    /** Thrown internally for "not a valid expression"; never escapes {@link #eval(String)}. */
    private static final class ParseError extends RuntimeException {
        ParseError() {
            super(null, null, false, false);   // no stack trace: this fires on every keystroke
        }
    }
}
