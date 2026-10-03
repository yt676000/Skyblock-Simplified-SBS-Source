/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.level;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Recolouring the level inside a line Hypixel already formatted.
 *
 * <p><b>The digits only.</b> A tab row is a styled tree - brackets in one colour, the number in
 * another, a rank prefix and sometimes an emblem in a third - and the tempting implementation
 * (flatten to a string, strip the codes, rebuild) destroys all of that to change one word. So the
 * component is walked run by run, and only the run holding the level's digits is rebuilt, with its
 * colour swapped and <i>every other property kept</i>: bold, italic, the click and hover events, and
 * every other run untouched.
 *
 * <p><b>Anything unexpected is left alone.</b> If the digits cannot be found - a wording nobody has
 * seen, an emblem glued to the number, a level written in a way this does not anticipate - the
 * original component is returned unchanged. A line the player still recognises is worth more than a
 * line this class was clever about.
 */
public final class LevelText {

    private LevelText() {
    }

    /**
     * {@code original} with the level's digits in the configured colour, or {@code original} itself
     * when the feature is off, the level is unknown, the band is a passthrough one, or the digits
     * are not where this expects them.
     */
    public static Component recolour(Component original, int level) {
        Integer rgb = LevelColors.colorOf(level);
        if (original == null || rgb == null) {
            return original;
        }
        List<Run> runs = runsOf(original);
        String digits = Integer.toString(level);
        for (int i = 0; i < runs.size(); i++) {
            int at = standaloneIndexOf(runs.get(i).text, digits);
            if (at >= 0) {
                return rebuild(runs, i, at, digits.length(), rgb);
            }
        }
        return original;   // not found: hand back exactly what came in
    }

    /** The component flattened into its styled runs, in order. */
    private static List<Run> runsOf(Component component) {
        List<Run> runs = new ArrayList<>(8);
        component.visit((style, text) -> {
            if (!text.isEmpty()) {
                runs.add(new Run(text, style));
            }
            return Optional.empty();
        }, Style.EMPTY);
        return runs;
    }

    /**
     * Where {@code digits} sits in {@code text} as a number in its own right.
     *
     * <p>The neighbour check is what stops level 52 from recolouring the "52" inside 520, and what
     * keeps a level that also appears in a percentage or a progress figure from being the one that
     * gets painted.
     */
    private static int standaloneIndexOf(String text, String digits) {
        int from = 0;
        while (true) {
            int at = text.indexOf(digits, from);
            if (at < 0) {
                return -1;
            }
            boolean leftClear = at == 0 || !Character.isDigit(text.charAt(at - 1));
            int after = at + digits.length();
            boolean rightClear = after >= text.length() || !Character.isDigit(text.charAt(after));
            if (leftClear && rightClear) {
                return at;
            }
            from = at + 1;
        }
    }

    /**
     * The same runs back, with run {@code index} split into what came before the digits, the digits
     * in the new colour, and what came after - each keeping the run's original style otherwise.
     */
    private static Component rebuild(List<Run> runs, int index, int at, int length, int rgb) {
        MutableComponent out = Component.empty();
        for (int i = 0; i < runs.size(); i++) {
            Run run = runs.get(i);
            if (i != index) {
                out.append(Component.literal(run.text).setStyle(run.style));
                continue;
            }
            String before = run.text.substring(0, at);
            String digits = run.text.substring(at, at + length);
            String after = run.text.substring(at + length);
            if (!before.isEmpty()) {
                out.append(Component.literal(before).setStyle(run.style));
            }
            // withColor on the run's own style, so bold / italic / events survive the recolour.
            out.append(Component.literal(digits)
                    .setStyle(run.style.withColor(TextColor.fromRgb(rgb))));
            if (!after.isEmpty()) {
                out.append(Component.literal(after).setStyle(run.style));
            }
        }
        return out;
    }

    /** One styled stretch of text, as {@link Component#visit} hands it over. */
    private record Run(String text, Style style) {
    }
}
