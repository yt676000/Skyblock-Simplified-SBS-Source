/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.damage.logic;

/**
 * The value and crit flag of one damage splash, read from the colour-stripped custom name of its
 * armor stand ({@code ChatFormatting.stripFormatting}). Runs once per name change, never per frame.
 *
 * <p>Accepted shape, identical to {@code NameClass.isDamageSplash} on the entity index: optional
 * whitespace, at most two {@code ✧}/{@code ✯} on each side, then digits with {@code ,} and
 * {@code .}, then an optional {@code k}/{@code M}/{@code B} suffix. The two must stay in step: a
 * name the index classifies as {@code DAMAGE_TAG} is a name this class can read.
 *
 * <ul>
 *   <li>{@code 12,345} - a normal hit.</li>
 *   <li>{@code ✧12,345✧} - a crit; Hypixel colours each character separately, which the strip
 *       removes.</li>
 *   <li>{@code 1.2M}, {@code 425k} - abbreviated. UNVERIFIED: not yet seen on a real splash; the
 *       shape is accepted because {@code DamageAttribution} already accepts it.</li>
 * </ul>
 *
 * <p>Without a suffix both {@code ,} and {@code .} are digit grouping ({@code 1.234.567} is one
 * million), matching {@code DamageAttribution.parseValue}. With a suffix {@code .} is the decimal
 * point. Allocation-free.
 */
public final class DamageSplash {

    /** Returned by {@link #value} when the name is not a damage splash. */
    public static final long NOT_A_SPLASH = -1L;

    private static final char CRIT = '✧';
    private static final char STAR = '✯';
    /** Past this many significant digits the value no longer fits a long; such a name is rejected. */
    private static final int MAX_DIGITS = 18;
    /** Decimals an abbreviated splash may carry; more is not a number Hypixel prints. */
    private static final int MAX_DECIMALS = 3;

    private DamageSplash() {
    }

    /** The damage a colour-stripped splash name shows, or {@link #NOT_A_SPLASH}. */
    public static long value(String plain) {
        if (plain == null) {
            return NOT_A_SPLASH;
        }
        int start = 0;
        int end = plain.length();
        while (start < end && plain.charAt(start) <= ' ') {
            start++;
        }
        while (end > start && plain.charAt(end - 1) <= ' ') {
            end--;
        }
        for (int n = 0; n < 2 && start < end && isStar(plain.charAt(start)); n++) {
            start++;
        }
        for (int n = 0; n < 2 && end > start && isStar(plain.charAt(end - 1)); n++) {
            end--;
        }
        if (start == end) {
            return NOT_A_SPLASH;
        }
        long scale = switch (plain.charAt(end - 1)) {
            case 'k', 'K' -> 1_000L;
            case 'm', 'M' -> 1_000_000L;
            case 'b', 'B' -> 1_000_000_000L;
            default -> 1L;
        };
        if (scale > 1L) {
            end--;
        }
        long mantissa = 0;
        int digits = 0;
        int fraction = -1;   // digits after the decimal point; -1 = no point seen
        for (int i = start; i < end; i++) {
            char c = plain.charAt(i);
            if (c >= '0' && c <= '9') {
                if (++digits > MAX_DIGITS) {
                    return NOT_A_SPLASH;
                }
                mantissa = mantissa * 10 + (c - '0');
                if (fraction >= 0 && ++fraction > MAX_DECIMALS) {
                    return NOT_A_SPLASH;
                }
            } else if (c == '.' && scale > 1L) {
                if (fraction >= 0) {
                    return NOT_A_SPLASH;   // "1.2.3k"
                }
                fraction = 0;
            } else if (c != ',' && c != '.') {
                return NOT_A_SPLASH;
            }
        }
        if (digits == 0) {
            return NOT_A_SPLASH;
        }
        if (fraction <= 0) {
            return mantissa > Long.MAX_VALUE / scale ? NOT_A_SPLASH : mantissa * scale;
        }
        long divisor = 1;
        for (int i = 0; i < fraction; i++) {
            divisor *= 10;
        }
        // Integer part and fraction scaled separately, so "1.25M" stays exact; the fraction is below
        // 10^MAX_DECIMALS, so its product with the scale cannot overflow.
        long whole = mantissa / divisor;
        long fractionValue = mantissa % divisor * scale / divisor;
        if (whole > (Long.MAX_VALUE - fractionValue) / scale) {
            return NOT_A_SPLASH;
        }
        return whole * scale + fractionValue;
    }

    /** Whether a colour-stripped splash name is a crit: a {@code ✧} or {@code ✯} anywhere in it. */
    public static boolean isCrit(String plain) {
        return plain != null && (plain.indexOf(CRIT) >= 0 || plain.indexOf(STAR) >= 0);
    }

    private static boolean isStar(char c) {
        return c == CRIT || c == STAR;
    }
}
