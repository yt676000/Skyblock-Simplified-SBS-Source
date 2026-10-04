/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.seymour.logic;

import sbs.modid.client.helper.seymour.model.ColourTarget;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * How special a leather colour is: its distance to known colours, and the classes and patterns its
 * hex falls into. Pure - no game objects - so all of it is unit-tested.
 *
 * <p>Colours are packed {@code 0xRRGGBB} ints. Distances are measured in CIE Lab (sRGB, D65 white):
 * {@link Metric#CIE76} is the plain Euclidean distance there, {@link Metric#CIEDE2000} the
 * perceptual formula that corrects CIE76's over-weighting of saturated colours. {@link #METRIC}
 * picks the one in use.
 */
public final class SeymourColour {

    /** The difference formula in use. CIE76 as specified; CIEDE2000 is the drop-in alternative. */
    public static final Metric METRIC = Metric.CIE76;

    public enum Metric { CIE76, CIEDE2000 }

    /** How close the best match is, best first. */
    public enum Tier {
        EXACT("exact"),
        NEAR("near"),
        CLOSE("close"),
        NONE("none");

        private final String label;

        Tier(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Fixed classes and hex patterns, in the order they are listed. */
    public enum Tag {
        PURE_GREY("Pure grey"),
        PURE_COLOUR("Pure colour"),
        WEB_SAFE("Web-safe"),
        PAIRED("Paired"),
        REPEATING("Repeating"),
        PALINDROME("Palindrome"),
        AXBXCX("AxBxCx");

        private final String label;

        Tag(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** CIE Lab coordinates. */
    public record Lab(double l, double a, double b) {
    }

    /**
     * The tier boundaries (inclusive upper ΔE of each tier). Built through {@link #of}, which keeps
     * them in order - a "near" bound below "exact" is lifted to it rather than producing a tier
     * nothing can reach.
     */
    public record Thresholds(double exact, double near, double close) {
        public static final Thresholds DEFAULT = new Thresholds(1.0, 2.0, 5.0);

        public static Thresholds of(double exact, double near, double close) {
            double e = Math.max(0, exact);
            double n = Math.max(e, near);
            double c = Math.max(n, close);
            return new Thresholds(e, n, c);
        }

        public Tier tierOf(double deltaE) {
            if (deltaE <= exact) {
                return Tier.EXACT;
            }
            if (deltaE <= near) {
                return Tier.NEAR;
            }
            return deltaE <= close ? Tier.CLOSE : Tier.NONE;
        }
    }

    /** One target at its distance from the analysed colour. */
    public record Match(ColourTarget target, double deltaE) {
    }

    /**
     * Everything said about one colour.
     *
     * @param matches nearest targets, closest first (at most the requested count)
     * @param tier    the tier of the closest match, {@link Tier#NONE} without any target
     * @param tags    classes and patterns, in {@link Tag} order
     * @param words   the player's words found in the hex, upper case, in the order given
     */
    public record Analysis(int rgb, List<Match> matches, Tier tier, Set<Tag> tags, List<String> words) {
        public Match best() {
            return matches.isEmpty() ? null : matches.get(0);
        }

        /** Tag labels then {@code Word X} entries, for one line of text. */
        public List<String> tagLabels() {
            List<String> out = new ArrayList<>(tags.size() + words.size());
            for (Tag tag : tags) {
                out.add(tag.label());
            }
            for (String word : words) {
                out.add("Word " + word);
            }
            return out;
        }
    }

    // D65 reference white (2 degree observer), and the CIE Lab constants.
    private static final double XN = 0.95047;
    private static final double YN = 1.0;
    private static final double ZN = 1.08883;
    private static final double DELTA = 6.0 / 29.0;

    private SeymourColour() {
    }

    // ------------------------------------------------------------------ hex

    /** {@code #RRGGBB}, upper case. */
    public static String hex(int rgb) {
        return String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
    }

    /**
     * Parses {@code RRGGBB} with or without {@code #}, case-insensitive; {@code -1} when it is not
     * exactly six hex digits.
     */
    public static int parseHex(String text) {
        if (text == null) {
            return -1;
        }
        String s = text.trim();
        if (s.startsWith("#")) {
            s = s.substring(1);
        }
        if (s.length() != 6) {
            return -1;
        }
        int value = 0;
        for (int i = 0; i < 6; i++) {
            int digit = Character.digit(s.charAt(i), 16);
            if (digit < 0) {
                return -1;
            }
            value = (value << 4) | digit;
        }
        return value;
    }

    // ------------------------------------------------------------------ Lab and ΔE

    /** sRGB (D65) to CIE Lab. */
    public static Lab toLab(int rgb) {
        double r = linear((rgb >> 16) & 0xFF);
        double g = linear((rgb >> 8) & 0xFF);
        double b = linear(rgb & 0xFF);
        double x = 0.4124564 * r + 0.3575761 * g + 0.1804375 * b;
        double y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b;
        double z = 0.0193339 * r + 0.1191920 * g + 0.9503041 * b;
        double fx = f(x / XN);
        double fy = f(y / YN);
        double fz = f(z / ZN);
        return new Lab(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz));
    }

    private static double linear(int channel) {
        double c = channel / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static double f(double t) {
        return t > DELTA * DELTA * DELTA ? Math.cbrt(t) : t / (3 * DELTA * DELTA) + 4.0 / 29.0;
    }

    /** The colour difference under {@link #METRIC}. */
    public static double deltaE(Lab a, Lab b) {
        return METRIC == Metric.CIEDE2000 ? ciede2000(a, b) : cie76(a, b);
    }

    public static double deltaE(int rgbA, int rgbB) {
        return deltaE(toLab(rgbA), toLab(rgbB));
    }

    /** CIE76: Euclidean distance in Lab. */
    public static double cie76(Lab a, Lab b) {
        double dl = a.l() - b.l();
        double da = a.a() - b.a();
        double db = a.b() - b.b();
        return Math.sqrt(dl * dl + da * da + db * db);
    }

    /**
     * CIEDE2000 with the reference weights kL = kC = kH = 1, following the formula as published by
     * the CIE (and the implementation notes of Sharma, Wu and Dalal, 2005).
     */
    public static double ciede2000(Lab lab1, Lab lab2) {
        double l1 = lab1.l();
        double a1 = lab1.a();
        double b1 = lab1.b();
        double l2 = lab2.l();
        double a2 = lab2.a();
        double b2 = lab2.b();

        double c1 = Math.hypot(a1, b1);
        double c2 = Math.hypot(a2, b2);
        double cMean = (c1 + c2) / 2;
        double cMean7 = Math.pow(cMean, 7);
        double g = 0.5 * (1 - Math.sqrt(cMean7 / (cMean7 + Math.pow(25, 7))));
        double a1p = (1 + g) * a1;
        double a2p = (1 + g) * a2;
        double c1p = Math.hypot(a1p, b1);
        double c2p = Math.hypot(a2p, b2);
        double h1p = hueDegrees(b1, a1p);
        double h2p = hueDegrees(b2, a2p);

        double dLp = l2 - l1;
        double dCp = c2p - c1p;
        double dhp;
        if (c1p * c2p == 0) {
            dhp = 0;
        } else if (Math.abs(h2p - h1p) <= 180) {
            dhp = h2p - h1p;
        } else if (h2p - h1p > 180) {
            dhp = h2p - h1p - 360;
        } else {
            dhp = h2p - h1p + 360;
        }
        double dHp = 2 * Math.sqrt(c1p * c2p) * Math.sin(Math.toRadians(dhp / 2));

        double lMean = (l1 + l2) / 2;
        double cMeanP = (c1p + c2p) / 2;
        double hMeanP;
        if (c1p * c2p == 0) {
            hMeanP = h1p + h2p;
        } else if (Math.abs(h1p - h2p) <= 180) {
            hMeanP = (h1p + h2p) / 2;
        } else if (h1p + h2p < 360) {
            hMeanP = (h1p + h2p + 360) / 2;
        } else {
            hMeanP = (h1p + h2p - 360) / 2;
        }

        double t = 1
                - 0.17 * Math.cos(Math.toRadians(hMeanP - 30))
                + 0.24 * Math.cos(Math.toRadians(2 * hMeanP))
                + 0.32 * Math.cos(Math.toRadians(3 * hMeanP + 6))
                - 0.20 * Math.cos(Math.toRadians(4 * hMeanP - 63));
        double dTheta = 30 * Math.exp(-Math.pow((hMeanP - 275) / 25, 2));
        double cMeanP7 = Math.pow(cMeanP, 7);
        double rc = 2 * Math.sqrt(cMeanP7 / (cMeanP7 + Math.pow(25, 7)));
        double lMean50 = (lMean - 50) * (lMean - 50);
        double sl = 1 + 0.015 * lMean50 / Math.sqrt(20 + lMean50);
        double sc = 1 + 0.045 * cMeanP;
        double sh = 1 + 0.015 * cMeanP * t;
        double rt = -Math.sin(Math.toRadians(2 * dTheta)) * rc;

        double termL = dLp / sl;
        double termC = dCp / sc;
        double termH = dHp / sh;
        return Math.sqrt(termL * termL + termC * termC + termH * termH + rt * termC * termH);
    }

    private static double hueDegrees(double b, double ap) {
        if (b == 0 && ap == 0) {
            return 0;
        }
        double h = Math.toDegrees(Math.atan2(b, ap));
        return h < 0 ? h + 360 : h;
    }

    // ------------------------------------------------------------------ targets

    /**
     * The {@code limit} targets closest to {@code rgb}, closest first. Targets sharing a name keep
     * only their closest entry, so one item listed by two sources does not fill the list twice. Ties
     * are broken by name so the order never depends on the input order.
     */
    public static List<Match> nearest(int rgb, Collection<ColourTarget> targets, int limit) {
        Lab lab = toLab(rgb);
        List<Match> all = new ArrayList<>(targets.size());
        for (ColourTarget target : targets) {
            all.add(new Match(target, deltaE(lab, target.lab())));
        }
        all.sort(Comparator.comparingDouble(Match::deltaE)
                .thenComparing(m -> m.target().name(), String.CASE_INSENSITIVE_ORDER));
        List<Match> out = new ArrayList<>(Math.min(limit, all.size()));
        Set<String> seen = new HashSet<>();
        for (Match match : all) {
            if (out.size() >= limit) {
                break;
            }
            if (seen.add(match.target().name().toLowerCase(Locale.ROOT))) {
                out.add(match);
            }
        }
        return out;
    }

    /** The full analysis of one colour. */
    public static Analysis analyse(int rgb, Collection<ColourTarget> targets, Thresholds thresholds,
                                   List<String> words, int limit) {
        List<Match> matches = nearest(rgb, targets, limit);
        Tier tier = matches.isEmpty() ? Tier.NONE : thresholds.tierOf(matches.get(0).deltaE());
        return new Analysis(rgb, matches, tier, tags(rgb), wordsIn(rgb, words));
    }

    // ------------------------------------------------------------------ classes and patterns

    /** Every class and pattern {@code rgb} falls into. */
    public static Set<Tag> tags(int rgb) {
        Set<Tag> out = EnumSet.noneOf(Tag.class);
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        if (r == g && g == b) {
            out.add(Tag.PURE_GREY);
        } else if (pure(r) && pure(g) && pure(b)) {
            out.add(Tag.PURE_COLOUR);
        }
        if (r % 0x33 == 0 && g % 0x33 == 0 && b % 0x33 == 0) {
            out.add(Tag.WEB_SAFE);
        }
        String s = hex(rgb).substring(1);
        if (s.charAt(0) == s.charAt(1) && s.charAt(2) == s.charAt(3) && s.charAt(4) == s.charAt(5)) {
            out.add(Tag.PAIRED);
        }
        if (s.regionMatches(0, s, 3, 3)) {
            out.add(Tag.REPEATING);
        }
        if (s.charAt(0) == s.charAt(5) && s.charAt(1) == s.charAt(4) && s.charAt(2) == s.charAt(3)) {
            out.add(Tag.PALINDROME);
        }
        if (s.charAt(1) == s.charAt(3) && s.charAt(3) == s.charAt(5)) {
            out.add(Tag.AXBXCX);
        }
        return out;
    }

    private static boolean pure(int channel) {
        return channel == 0x00 || channel == 0xFF;
    }

    /**
     * The player's words (2-6 hex digits each, see {@link #parseWords}) found anywhere in the hex,
     * in the order given.
     */
    public static List<String> wordsIn(int rgb, List<String> words) {
        if (words == null || words.isEmpty()) {
            return List.of();
        }
        String s = hex(rgb).substring(1);
        List<String> out = new ArrayList<>();
        for (String word : words) {
            if (s.contains(word)) {
                out.add(word);
            }
        }
        return out;
    }

    /**
     * Splits the configured word list (commas, spaces or semicolons) into upper-case words of 2-6
     * hex digits, a leading {@code #} allowed. Anything else is dropped; duplicates are kept once.
     */
    public static List<String> parseWords(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : raw.split("[,;\\s]+")) {
            String word = part.trim();
            if (word.startsWith("#")) {
                word = word.substring(1);
            }
            word = word.toUpperCase(Locale.ROOT);
            if (word.length() < 2 || word.length() > 6 || !word.chars().allMatch(SeymourColour::isHexDigit)) {
                continue;
            }
            if (!out.contains(word)) {
                out.add(word);
            }
        }
        return out;
    }

    private static boolean isHexDigit(int c) {
        return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'F');
    }

    /** {@code 0.8}-style one-decimal ΔE text. */
    public static String formatDeltaE(double deltaE) {
        return String.format(Locale.ROOT, "%.1f", deltaE);
    }
}
