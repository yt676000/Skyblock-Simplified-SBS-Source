/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.config.share;

import sbs.modid.client.core.keybind.Keys;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The {@link Kind} value contract: what an imported value has to satisfy before it may reach the
 * config, and what an exported one is written as.
 *
 * <p>Every check rejects rather than coerces. A number outside its range is not clamped and an
 * unknown enum name is not guessed at, because both turn "this payload is wrong" into "this setting
 * now says something you did not choose" - and only one of those is visible to the person it happens
 * to.
 */
public final class ShareValues {

    /** {@code RRGGBB}, or empty for "inherit from the theme". */
    private static final Pattern HEX = Pattern.compile("[0-9A-Fa-f]{6}");

    /** A registry id: lower case, digits and underscores. */
    private static final Pattern ID = Pattern.compile("[a-z0-9_]{1,64}");

    /** Longest a {@link Kind#TEXT} label may be. */
    public static final int MAX_TEXT = 64;

    /** Longest any imported string may be, whatever its kind. */
    public static final int MAX_STRING = 256;

    /** Most elements any imported collection may hold. */
    public static final int MAX_ELEMENTS = 512;

    /**
     * The highest a bind code may be: the top of {@link Keys}' own code space.
     *
     * <p>Taken from {@code Keys} rather than written as a number, because the number was wrong. This
     * was {@code 512} with the note "mouse buttons are small positive numbers" - true of GLFW's
     * button indices, and not true of what this config stores. A bind here is one int covering keys
     * <i>and</i> mouse buttons ({@code MOUSE_BASE + button}, from 10 000) <i>and</i> the two wheel
     * directions, so every mouse and wheel bind in the mod exported fine and was refused on the way
     * back in. No test caught it because every keybind field <b>defaults</b> to unbound, and only a
     * player who had actually bound a mouse button would ever have hit it.
     */
    private static final int MAX_KEYCODE = Keys.WHEEL_DOWN;

    /**
     * The lowest a key code may be: {@code GLFW_KEY_UNKNOWN}.
     *
     * <p>Two conventions for "no key bound" live in the config - most keybind fields default to
     * {@code 0}, five (Ether Warp sensitivity, Fullbright, the two farming keys, the Overlay
     * Inspector) default to GLFW's own {@code -1}. Both are inert, and refusing one of them meant
     * those five settings could be exported and never imported: the payload carried {@code -1}, the
     * contract said 0-512, and the key was dropped with a reason nobody could act on.
     */
    private static final int MIN_KEYCODE = -1;

    private ShareValues() {
    }

    /** Why a value was refused, for the preview's per-key reason. */
    public record Rejected(String path, String reason) {
    }

    /**
     * {@code raw} as the value {@code spec} describes, or empty when it does not qualify.
     *
     * @param raw a value already read from JSON as a {@link Boolean}, {@link Number} or
     *            {@link String} - never an object, because the reader never materialises one
     */
    public static Optional<Object> accept(Shareable spec, Class<?> target, Object raw,
                                          StringBuilder reason) {
        if (raw == null) {
            reason.append("no value");
            return Optional.empty();
        }
        return switch (spec.value()) {
            case BOOL -> acceptBool(raw, reason);
            case INT -> acceptInt(raw, spec.min(), spec.max(), target, reason);
            case PERCENT -> acceptInt(raw, 0, 100, target, reason);
            case KEYCODE -> acceptInt(raw, MIN_KEYCODE, MAX_KEYCODE, target, reason);
            case HEX_COLOR -> acceptHex(raw, reason);
            case ENUM -> acceptEnum(spec, target, raw, reason);
            case ID -> acceptId(raw, reason);
            case TEXT -> acceptText(raw, reason);
            case SIDEBAR_LAYOUT -> acceptLayout(raw, reason);
            // The collection and transform kinds are declared so the annotation can express them,
            // but nothing is annotated with one yet. Refusing beats a half-written reader that
            // looks like it validates and does not.
            case ID_LIST, ID_SET, TRANSFORM -> {
                reason.append("this kind of setting cannot be imported yet");
                yield Optional.empty();
            }
        };
    }

    private static Optional<Object> acceptBool(Object raw, StringBuilder reason) {
        if (raw instanceof Boolean bool) {
            return Optional.of(bool);
        }
        reason.append("expected true or false");
        return Optional.empty();
    }

    private static Optional<Object> acceptInt(Object raw, int min, int max, Class<?> target,
                                              StringBuilder reason) {
        if (!(raw instanceof Number number)) {
            reason.append("expected a number");
            return Optional.empty();
        }
        double value = number.doubleValue();
        if (value != Math.floor(value) || Double.isInfinite(value)) {
            reason.append("expected a whole number");
            return Optional.empty();
        }
        long asLong = (long) value;
        if (asLong < min || asLong > max) {
            reason.append("outside ").append(min).append("-").append(max);
            return Optional.empty();
        }
        if (target == long.class || target == Long.class) {
            return Optional.of(asLong);
        }
        return Optional.of((int) asLong);
    }

    private static Optional<Object> acceptHex(Object raw, StringBuilder reason) {
        if (!(raw instanceof String text)) {
            reason.append("expected a colour");
            return Optional.empty();
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return Optional.of("");   // "inherit from the theme" is a legitimate value
        }
        if (!HEX.matcher(trimmed).matches()) {
            reason.append("not a six-digit colour");
            return Optional.empty();
        }
        return Optional.of(trimmed.toUpperCase(Locale.ROOT));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Optional<Object> acceptEnum(Shareable spec, Class<?> target, Object raw,
                                               StringBuilder reason) {
        if (!(raw instanceof String name)) {
            // Never an ordinal. An ordinal is a position, and inserting a constant silently
            // repoints every stored number at a different setting.
            reason.append("expected a name, not a number");
            return Optional.empty();
        }
        Class<?> type = spec.enumType() != Shareable.DefaultNone.class ? spec.enumType() : target;
        if (type == null || !type.isEnum()) {
            reason.append("no such choice list");
            return Optional.empty();
        }
        for (Object constant : type.getEnumConstants()) {
            if (((Enum) constant).name().equalsIgnoreCase(name.trim())) {
                return Optional.of(constant);
            }
        }
        reason.append("not one of the available choices");
        return Optional.empty();
    }

    /** A sidebar layout: empty for the default, otherwise whatever the strict decoder accepts. */
    private static Optional<Object> acceptLayout(Object raw, StringBuilder reason) {
        if (!(raw instanceof String text) || text.length() > 32_768) {
            reason.append("expected a sidebar layout");
            return Optional.empty();
        }
        if (text.isEmpty()) {
            return Optional.of("");
        }
        var layout = sbs.modid.client.ui.settings.layout.SidebarLayout.decode(text);
        if (layout == null) {
            reason.append("not a valid sidebar layout");
            return Optional.empty();
        }
        return Optional.of(layout.encode());
    }

    private static Optional<Object> acceptId(Object raw, StringBuilder reason) {
        if (!(raw instanceof String text) || !ID.matcher(text.trim()).matches()) {
            reason.append("not a valid id");
            return Optional.empty();
        }
        return Optional.of(text.trim());
    }

    /**
     * A label, capped and stripped.
     *
     * <p>{@code §} codes go because a colour code in an imported label can hide text or forge the
     * look of a system message; control characters and newlines go because a HUD label is one line
     * and anything else is a way to push other text off the screen.
     */
    private static Optional<Object> acceptText(Object raw, StringBuilder reason) {
        if (!(raw instanceof String text)) {
            reason.append("expected text");
            return Optional.empty();
        }
        StringBuilder clean = new StringBuilder(Math.min(text.length(), MAX_TEXT));
        for (int i = 0; i < text.length() && clean.length() < MAX_TEXT; i++) {
            char c = text.charAt(i);
            if (c == '§') {
                i++;        // drop the code character with it
                continue;
            }
            if (c == '\n' || c == '\r' || Character.isISOControl(c)) {
                continue;
            }
            clean.append(c);
        }
        return Optional.of(clean.toString());
    }
}
