/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.util;

/**
 * Writing the numbers in a line out as words, so a speech synthesizer reads them in the language the
 * rest of the sentence is in.
 *
 * <h2>The bug this exists for</h2>
 * Speech goes out through the operating system's own voice ({@link
 * sbs.modid.client.core.alert.NarratorVoice}), and that voice expands digits by <b>its own</b> locale
 * rules, not by the language of the text it was handed. On a machine whose default voice is German,
 * "Pest spawned. Plot 4" comes out as English words with a German "vier" in the middle of it - the
 * letters are merely accented, but the digit is fully translated, which is why only the numbers
 * stand out. The synthesizer cannot be told otherwise: {@code com.mojang.text2speech.Narrator}
 * exposes {@code say(text, interrupt, volume)} and nothing else, its Windows backend binds only the
 * Speak / Skip / SetVolume slots of {@code ISpVoice} (never {@code SetVoice}), and it passes
 * {@code SPF_IS_NOT_XML}, which rules out the SAPI markup route as well. So the digit has to be gone
 * before the text ever reaches it.
 *
 * <p>Spelling a number out takes the decision away from the voice: {@code four} is read as an English
 * word by an English voice and as an accented English word by a German one, but it is never
 * translated. That is the whole trick - the announcement ends up consistently in <i>one</i> language
 * instead of mixed, which is what the player actually noticed.
 *
 * <h2>Where this may be used</h2>
 * <b>At the boundary to the speech call, and nowhere else.</b> Chat, HUD text, titles and the log
 * keep their digits: 12 characters of "Plot 4" is what a player reads comfortably, and
 * "Plot four" in a log line is worse for everyone. Nothing here belongs at the point a message is
 * built.
 *
 * <h2>What is left alone</h2>
 * The rules for finding a figure at all - letter adjacency, {@code §} colour codes, trailing
 * punctuation - are {@link NumberScan}'s and are shared with the number shortener. On top of them
 * this leaves untouched anything that is not a plain value:
 * <ul>
 *   <li>version numbers: more than one decimal point ({@code 1.2.3}), or a letter in front
 *       ({@code v1.2});</li>
 *   <li>ids: a digit run touching a letter ({@code mini123AB}), and any run written with a leading
 *       zero ({@code 007}, {@code 01}) - a value is not padded, an identifier is;</li>
 *   <li>coordinate-like, clock-like and date-like tokens: a figure with {@code :}, {@code /},
 *       {@code \}, {@code -} or {@code .} still against it, so {@code 12:34}, {@code 187/120/-430},
 *       {@code 2024-01-15} and {@code Tier-3} are read as written;</li>
 *   <li>separators that do not group in threes ({@code 12,34}), and runs longer than a
 *       {@code long} holds - both are far likelier to be an identifier than a number.</li>
 * </ul>
 *
 * <h2>Digits are read as Hypixel writes them</h2>
 * {@code ,} groups thousands and {@code .} is the decimal point, in both languages. The text being
 * spoken is Hypixel's or SBS's own, and both write numbers that way ({@link NumberDisplay} formats
 * with {@code Locale.ROOT}); a German player reading "1,234" is still reading one thousand two
 * hundred and thirty-four. Reading the comma as a German decimal separator would silently divide
 * every large number by a thousand.
 */
public final class SpokenNumbers {

    /** Which language the spelled-out words are in. */
    public enum Language {
        ENGLISH,
        GERMAN
    }

    // ------------------------------------------------------------------ English words

    private static final String[] EN_ONES = {
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
            "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen",
            "eighteen", "nineteen"};

    private static final String[] EN_TENS = {
            "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"};

    // ------------------------------------------------------------------ German words

    private static final String[] DE_ONES = {
            "null", "eins", "zwei", "drei", "vier", "fünf", "sechs", "sieben", "acht", "neun",
            "zehn", "elf", "zwölf", "dreizehn", "vierzehn", "fünfzehn", "sechzehn",
            "siebzehn", "achtzehn", "neunzehn"};

    private static final String[] DE_TENS = {
            "", "", "zwanzig", "dreißig", "vierzig", "fünfzig", "sechzig", "siebzig",
            "achtzig", "neunzig"};

    /** The scale words, largest first. English multiplies; German counts nouns, hence the plural. */
    private static final long[] SCALE_STEP = {1_000_000_000_000L, 1_000_000_000L, 1_000_000L};
    private static final String[] EN_SCALE = {"trillion", "billion", "million"};
    private static final String[] DE_SCALE_ONE = {"eine Billion", "eine Milliarde", "eine Million"};
    private static final String[] DE_SCALE_MANY = {"Billionen", "Milliarden", "Millionen"};

    /** Longer than this and a run of digits is an identifier, not a quantity anyone says out loud. */
    private static final int MAX_DIGITS = 15;

    private SpokenNumbers() {
    }

    /**
     * {@code text} with every plain figure in it written out as words.
     *
     * @return the rewritten text, or the input itself when it holds nothing to write out
     */
    public static String rewrite(String text, Language language) {
        if (text == null || text.isEmpty() || !NumberScan.hasDigit(text)) {
            return text;
        }
        Language lang = language == null ? Language.ENGLISH : language;
        // The percent sign first, and as a word with a space in front of it: the scan that follows
        // reads figures, not symbols, and "50 percent" then spells out exactly like any other value.
        String expanded = expandPercent(text, lang);
        return NumberScan.rewrite(expanded, (token, preceding, following) ->
                tiesToSomethingElse(preceding) || tiesToSomethingElse(following)
                        ? null : words(token, lang));
    }

    /**
     * A figure written out, or {@code null} when it is not a plain value.
     *
     * <p>Public because it is the half worth testing directly, and because a caller that already has
     * the figure on its own has no line for {@link #rewrite} to scan.
     */
    public static String words(String token, Language language) {
        Language lang = language == null ? Language.ENGLISH : language;
        String digits = token;
        boolean negative = digits.startsWith("-");
        if (negative) {
            digits = digits.substring(1);
        }
        int dot = digits.indexOf('.');
        if (dot != digits.lastIndexOf('.')) {
            return null;   // 1.2.3 - a version, not a number
        }
        String whole = dot < 0 ? digits : digits.substring(0, dot);
        String fraction = dot < 0 ? "" : digits.substring(dot + 1);
        if (whole.isEmpty() || (dot >= 0 && fraction.isEmpty())) {
            return null;
        }
        if (!groupsInThrees(whole)) {
            return null;
        }
        whole = whole.replace(",", "");
        if (!fraction.isEmpty() && !isDigits(fraction)) {
            return null;   // a separator after the point is not a decimal place
        }
        // "007" and "01" are padded, and padding is what identifiers and clocks do, not values.
        if (whole.length() > 1 && whole.charAt(0) == '0') {
            return null;
        }
        if (whole.length() > MAX_DIGITS) {
            return null;
        }

        long value;
        try {
            value = Long.parseLong(whole);
        } catch (NumberFormatException notANumber) {
            return null;
        }
        StringBuilder out = new StringBuilder(32);
        if (negative) {
            out.append("minus ");
        }
        out.append(integerWords(value, lang, true));
        if (!fraction.isEmpty()) {
            // Digit by digit after the point, the way a person reads one out: "two point five",
            // "zero point two five" - never "two point twenty-five", which is a different number.
            out.append(lang == Language.GERMAN ? " Komma" : " point");
            for (int i = 0; i < fraction.length(); i++) {
                out.append(' ').append(digitWord(fraction.charAt(i) - '0', lang));
            }
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ the conversion

    /**
     * A whole number as words.
     *
     * @param standalone whether nothing follows this in the same word - German alone cares, because
     *                   its {@code 1} is "eins" only at the end ("eins", but "einundzwanzig",
     *                   "einhundert", "eintausend")
     */
    private static String integerWords(long value, Language language, boolean standalone) {
        return language == Language.GERMAN ? german(value, standalone) : english(value);
    }

    private static String english(long value) {
        if (value < 20) {
            return EN_ONES[(int) value];
        }
        for (int i = 0; i < SCALE_STEP.length; i++) {
            if (value >= SCALE_STEP[i]) {
                return withRest(english(value / SCALE_STEP[i]) + " " + EN_SCALE[i],
                        value % SCALE_STEP[i], Language.ENGLISH, " ");
            }
        }
        if (value >= 1000) {
            return withRest(english(value / 1000) + " thousand", value % 1000, Language.ENGLISH, " ");
        }
        if (value >= 100) {
            return withRest(EN_ONES[(int) (value / 100)] + " hundred", value % 100,
                    Language.ENGLISH, " ");
        }
        long unit = value % 10;
        return EN_TENS[(int) (value / 10)] + (unit == 0 ? "" : "-" + EN_ONES[(int) unit]);
    }

    /**
     * German, which differs from English in three ways that all matter out loud: the units come
     * first below a hundred ("einundzwanzig"), everything below a million is one unbroken word, and
     * the scale words are nouns that are counted rather than multipliers ("eine Million", "zwei
     * Millionen") - with "Milliarde" where English says billion.
     */
    private static String german(long value, boolean standalone) {
        if (value < 20) {
            return value == 1 && !standalone ? "ein" : DE_ONES[(int) value];
        }
        for (int i = 0; i < SCALE_STEP.length; i++) {
            if (value >= SCALE_STEP[i]) {
                long count = value / SCALE_STEP[i];
                String head = count == 1 ? DE_SCALE_ONE[i]
                        : german(count, false) + " " + DE_SCALE_MANY[i];
                return withRest(head, value % SCALE_STEP[i], Language.GERMAN, " ");
            }
        }
        // Below a million German writes one word, so the rest joins with nothing between it and the
        // scale: "eintausendzweihundert", not "eintausend zweihundert".
        if (value >= 1000) {
            return withRest(german(value / 1000, false) + "tausend", value % 1000,
                    Language.GERMAN, "");
        }
        if (value >= 100) {
            return withRest(german(value / 100, false) + "hundert", value % 100, Language.GERMAN, "");
        }
        long unit = value % 10;
        String tens = DE_TENS[(int) (value / 10)];
        return unit == 0 ? tens : german(unit, false) + "und" + tens;
    }

    /** {@code head}, plus what is left of the number after it when that is not zero. */
    private static String withRest(String head, long rest, Language language, String separator) {
        return rest == 0 ? head : head + separator + integerWords(rest, language, true);
    }

    /** A single digit, for reading a fraction out one figure at a time. */
    private static String digitWord(int digit, Language language) {
        return language == Language.GERMAN ? DE_ONES[digit] : EN_ONES[digit];
    }

    // ------------------------------------------------------------------ the line around the figure

    /**
     * {@code %} directly after a digit written out as a word.
     *
     * <p>Only there: a stray {@code %} in prose is somebody's punctuation, and {@code 100%} is the
     * only shape the alerts actually produce.
     */
    private static String expandPercent(String text, Language language) {
        int at = text.indexOf('%');
        if (at < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '%' && i > 0 && NumberScan.isDigit(text.charAt(i - 1))) {
                out.append(language == Language.GERMAN ? " Prozent" : " percent");
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Whether {@code c} ties the figure into something that is not a quantity - a clock, a date, a
     * path or a version - so it is read exactly as it is written.
     *
     * <p>{@code :} {@code /} {@code \} are the clock, date and coordinate joins: {@code 12:34} and
     * {@code 187/120/-430}.
     *
     * <p>{@code -} and {@code .} are here because of what it means for one to be <i>next to</i> a
     * figure rather than inside it. {@link NumberScan} already takes a decimal point and a leading
     * sign into the figure itself, so a dash or a dot that is still sitting beside the token is one
     * it refused: the dash in {@code Tier-3} and {@code 2024-01-15} is a hyphen joining words or
     * date parts, and the dot in front of the {@code 2} in {@code v1.2} is the rest of a version
     * whose first half was already ruled out for touching a letter.
     */
    private static boolean tiesToSomethingElse(char c) {
        return c == ':' || c == '/' || c == '\\' || c == '-' || c == '.';
    }

    /**
     * Whether the commas in {@code whole} group it in threes, the only way a thousands separator is
     * ever written. {@code 12,34} is something else and is left alone.
     */
    private static boolean groupsInThrees(String whole) {
        int comma = whole.indexOf(',');
        if (comma < 0) {
            return isDigits(whole);
        }
        String[] groups = whole.split(",", -1);
        if (groups[0].isEmpty() || groups[0].length() > 3 || !isDigits(groups[0])) {
            return false;
        }
        for (int i = 1; i < groups.length; i++) {
            if (groups[i].length() != 3 || !isDigits(groups[i])) {
                return false;
            }
        }
        return true;
    }

    private static boolean isDigits(String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (!NumberScan.isDigit(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
