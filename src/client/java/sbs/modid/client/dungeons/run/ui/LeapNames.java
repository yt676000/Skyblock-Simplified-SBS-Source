/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.run.ui;

import java.util.function.ToIntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which player a Spirit Leap head stands for, as a pure function so it can be tested.
 *
 * <p>The head's display name is not reliable on its own: the mod's own item renamer and text
 * replacements rewrite {@code getHoverName()}, and Hypixel may prefix a rank ("[MVP+] Name"). The
 * head's skin profile carries the real IGN and none of that, so it is tried first; the display name
 * is the fallback for heads without a usable profile.
 */
public final class LeapNames {

    private static final Pattern IGN = Pattern.compile("[A-Za-z0-9_]{1,16}");
    /** The last IGN-shaped word in a display name, after any "[RANK]" / "[212]" brackets. */
    private static final Pattern LAST_WORD = Pattern.compile("([A-Za-z0-9_]{1,16})\\W*$");
    private static final Pattern BRACKETS = Pattern.compile("\\[[^\\]]*\\]");

    private LeapNames() {
    }

    /** The IGN in a colour-stripped head display name, or null ("[MVP+] Name" gives "Name"). */
    public static String fromDisplay(String stripped) {
        if (stripped == null) {
            return null;
        }
        String rest = BRACKETS.matcher(stripped).replaceAll(" ").trim();
        if (rest.isEmpty()) {
            return null;
        }
        if (IGN.matcher(rest).matches()) {
            return rest;
        }
        Matcher m = LAST_WORD.matcher(rest);
        return m.find() ? m.group(1) : rest;
    }

    /** True when {@code name} looks like a Minecraft IGN. */
    public static boolean isIgn(String name) {
        return name != null && IGN.matcher(name).matches();
    }

    /**
     * Picks the head's player name: the first candidate (profile, then display) that has a known
     * class wins; with no class known for either, the profile name when it is an IGN, else the
     * display name. {@code classOf} answers 0 for "no class known".
     */
    public static String choose(String profileName, String displayStripped, ToIntFunction<String> classOf) {
        String profile = isIgn(profileName) ? profileName : null;
        String display = fromDisplay(displayStripped);
        if (profile != null && classOf.applyAsInt(profile) != 0) {
            return profile;
        }
        if (display != null && classOf.applyAsInt(display) != 0) {
            return display;
        }
        return profile != null ? profile : display;
    }
}
