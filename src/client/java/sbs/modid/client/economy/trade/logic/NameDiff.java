/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.trade.logic;

/**
 * Marks where two near-identical names differ, so "DR-X655" vs "DR-X355" cannot be read past: the
 * common prefix and suffix stay grey, the differing middle is coloured and bold. That middle is the
 * whole point - the eye reads the shape of a name, not each character.
 */
public final class NameDiff {

    /** Both names with the differing run marked. {@code a} in green (what was shown), {@code b} in red. */
    public record Marked(String before, String after, String beforePart, String afterPart) {
    }

    private NameDiff() {
    }

    public static Marked mark(String a, String b) {
        a = a == null ? "" : a;
        b = b == null ? "" : b;
        int pre = 0;
        int max = Math.min(a.length(), b.length());
        while (pre < max && a.charAt(pre) == b.charAt(pre)) {
            pre++;
        }
        int suf = 0;
        while (suf < max - pre && a.charAt(a.length() - 1 - suf) == b.charAt(b.length() - 1 - suf)) {
            suf++;
        }
        String partA = a.substring(pre, a.length() - suf);
        String partB = b.substring(pre, b.length() - suf);
        return new Marked(render(a, pre, suf, "§a"), render(b, pre, suf, "§c"), partA, partB);
    }

    private static String render(String s, int pre, int suf, String colour) {
        String mid = s.substring(pre, s.length() - suf);
        return "§7" + s.substring(0, pre) + (mid.isEmpty() ? "" : colour + "§l§n" + mid + "§r§7")
                + s.substring(s.length() - suf);
    }
}
