/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.entity;

/**
 * What a custom name is, decided once per name change from its colour-stripped text. Cheap enough
 * that consumers reject on it before reading the name at all.
 *
 * <p>Order matters: a crit damage splash ("✧12,345✧", also "✯…✯") is a {@link #DAMAGE_TAG}, not a
 * {@link #STARRED} mob; a starred mob tag that also carries health stays {@link #STARRED}.
 */
public enum NameClass {
    /** No custom name, or an empty one. */
    NONE,
    /** A dungeon starred-mob tag: carries ✯. */
    STARRED,
    /** A damage splash: digits with , . k/M/B, wrapped in at most two ✧/✯ on each side. */
    DAMAGE_TAG,
    /** A mob nametag with a health readout ("[Lv250] … 3M/3M❤"). */
    HEALTH_TAG,
    /** The "CLICK" label under an NPC's name. */
    NPC_LABEL,
    /** A pet nametag: "[Lv100] Someone's Golden Dragon" (no health). */
    PET,
    /** Anything else: NPC names, holograms, plain-named mobs. */
    PLAIN;

    private static final char STAR = '✯';
    private static final char CRIT = '✧';
    private static final char HEART = '❤';

    /** The class of a colour-stripped name. Pure, allocation-free. */
    public static NameClass classify(String plain) {
        if (plain == null) {
            return NONE;
        }
        int start = 0;
        int end = plain.length();
        while (start < end && plain.charAt(start) <= ' ') {
            start++;
        }
        while (end > start && plain.charAt(end - 1) <= ' ') {
            end--;
        }
        if (start == end) {
            return NONE;
        }
        if (isDamageSplash(plain, start, end)) {
            return DAMAGE_TAG;
        }
        if (plain.indexOf(STAR, start) >= 0) {
            return STARRED;
        }
        if (plain.indexOf(HEART, start) >= 0) {
            return HEALTH_TAG;
        }
        if (plain.regionMatches(start, "CLICK", 0, 5) && end - start == 5) {
            return NPC_LABEL;
        }
        if (plain.startsWith("[Lv", start) && plain.indexOf("'s ", start) >= 0) {
            return PET;
        }
        return PLAIN;
    }

    /**
     * The owner of a {@link #PET} name: "[Lv100] Someone's Golden Dragon" → "Someone", skipping
     * further bracket tags after the level ("[Lv100] [MVP+] Someone's …"). {@code null} when
     * {@code plain} is no pet name or the owner part is not a single word. Allocates the result;
     * callers cache it per name change ({@link Tracked#petOwner}).
     */
    public static String petOwner(String plain) {
        if (plain == null) {
            return null;
        }
        int start = 0;
        while (start < plain.length() && plain.charAt(start) <= ' ') {
            start++;
        }
        if (!plain.startsWith("[Lv", start)) {
            return null;
        }
        int i = start;
        while (i < plain.length() && plain.charAt(i) == '[') {
            int close = plain.indexOf("] ", i);
            if (close < 0) {
                return null;
            }
            i = close + 2;
        }
        int end = plain.indexOf("'s ", i);
        if (end <= i) {
            return null;
        }
        for (int k = i; k < end; k++) {
            if (plain.charAt(k) <= ' ') {
                return null;
            }
        }
        return plain.substring(i, end);
    }

    /** Hand-written {@code ^[✧✯]{0,2}([\d.,]+[kKmMbB]?)[✧✯]{0,2}$} over {@code [start, end)}. */
    static boolean isDamageSplash(String s, int start, int end) {
        int i = start;
        for (int n = 0; n < 2 && i < end && isStar(s.charAt(i)); n++) {
            i++;
        }
        int j = end;
        for (int n = 0; n < 2 && j > i && isStar(s.charAt(j - 1)); n++) {
            j--;
        }
        if (j > i && "kKmMbB".indexOf(s.charAt(j - 1)) >= 0) {
            j--;
        }
        if (j == i) {
            return false;
        }
        for (int k = i; k < j; k++) {
            char c = s.charAt(k);
            if ((c < '0' || c > '9') && c != '.' && c != ',') {
                return false;
            }
        }
        return true;
    }

    private static boolean isStar(char c) {
        return c == CRIT || c == STAR;
    }
}
