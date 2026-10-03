/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.wizard.model;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A {@code major.minor.patch} mod version, for deciding which showcase pages a returning player has
 * not seen yet.
 *
 * <p><b>Parsing can fail, and every caller has to have an answer for that.</b> This is not a
 * defensive flourish: {@code fabric.mod.json} currently declares {@code "version": "PreAlpha"}, so
 * the version of the build this class runs inside does not parse today. A design that assumed a
 * version is always available would therefore be broken on the only build that exists.
 *
 * <p>{@link #parse} is deliberately strict - it rejects rather than guesses. A lenient parser that
 * turned {@code "PreAlpha"} into {@code 0.0.0} would silently mark every page as "introduced after
 * the player's version" and show all of them, which is the exact failure the showcase cap exists to
 * prevent. Refusing to parse is visible; guessing is not.
 *
 * <p>A pre-release or build suffix is accepted and ignored: {@code 1.2.0-rc1} and {@code 1.2.0}
 * compare equal, because a player who saw the release candidate's pages has seen those pages.
 */
public record ModVersion(int major, int minor, int patch) implements Comparable<ModVersion> {

    /**
     * {@code 1}, {@code 1.2} and {@code 1.2.3}, optionally {@code v}-prefixed, optionally followed by
     * a {@code -pre} / {@code +build} suffix that is matched so it can be discarded. Anything else -
     * a word, an empty string, a negative number - is not a version.
     */
    private static final Pattern SHAPE =
            Pattern.compile("^v?(\\d{1,6})(?:\\.(\\d{1,6}))?(?:\\.(\\d{1,6}))?(?:[-+].*)?$");

    /** The zero version. Only ever produced deliberately, never as a parse fallback. */
    public static final ModVersion ZERO = new ModVersion(0, 0, 0);

    /**
     * Reads a version, or empty when the text is not one.
     *
     * @param text a declared or persisted version string; {@code null} and blank are not versions
     */
    public static Optional<ModVersion> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = SHAPE.matcher(text.trim());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(new ModVersion(
                group(matcher, 1), group(matcher, 2), group(matcher, 3)));
    }

    private static int group(Matcher matcher, int index) {
        String value = matcher.group(index);
        return value == null ? 0 : Integer.parseInt(value);
    }

    @Override
    public int compareTo(ModVersion other) {
        if (major != other.major) {
            return Integer.compare(major, other.major);
        }
        if (minor != other.minor) {
            return Integer.compare(minor, other.minor);
        }
        return Integer.compare(patch, other.patch);
    }

    public boolean isAfter(ModVersion other) {
        return compareTo(other) > 0;
    }

    public boolean isBefore(ModVersion other) {
        return compareTo(other) < 0;
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
