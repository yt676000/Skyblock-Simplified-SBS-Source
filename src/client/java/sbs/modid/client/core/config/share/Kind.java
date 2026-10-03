/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.config.share;

/**
 * What a {@link Shareable} value is, and therefore what an imported one must satisfy.
 *
 * <p><b>There is no kind for a URL, a path or a command.</b> That is the point of having kinds at
 * all: the set of things that can be expressed is the set of things that can be shared, so excluding
 * a category is a matter of not being able to say it rather than of remembering to filter it.
 *
 * <p>Every kind validates on import. A value that does not satisfy its kind is rejected with a
 * reason - never coerced, never clamped silently, never best-effort applied.
 */
public enum Kind {

    /** A JSON boolean. Nothing else - not 0/1, not "true". */
    BOOL,

    /**
     * An integral JSON number within the annotation's {@code min}/{@code max}, which are required.
     * The bounds come from the setting's own UI, so an import cannot reach a value the player could
     * not have set by hand.
     */
    INT,

    /** {@link #INT} fixed to 0-100. */
    PERCENT,

    /**
     * An enum, matched by constant <b>name</b> against {@code enumType()}.
     *
     * <p>Never by ordinal: an ordinal is a position, and inserting a constant silently changes what
     * every stored number means. An unknown name is rejected rather than guessed.
     */
    ENUM,

    /** {@code RRGGBB}, or empty for "inherit from the theme". Six hex digits, nothing else. */
    HEX_COLOR,

    /**
     * A GLFW key or mouse code, or unbound - which is {@code 0} in most of the config and GLFW's
     * own {@code -1} in a handful of places. Both are accepted; see {@code ShareValues}.
     *
     * <p>Safe because a key code is inert: it selects <i>when</i> something happens, never what.
     * The action a key runs is not shareable and has no kind here - see the design doc's §4b.
     */
    KEYCODE,

    /**
     * A lower-case identifier that must resolve against a live registry - a scoreboard element, a
     * particle, a mob name. Unresolvable ids are dropped with a note rather than stored, so a
     * payload cannot seed the config with ids that mean nothing to this build.
     */
    ID,

    /** A collection of {@link #ID}. */
    ID_LIST,

    /** A set of {@link #ID}. */
    ID_SET,

    /**
     * A short label the player sees.
     *
     * <p>The only kind carrying free text, and the most restricted: capped, and stripped of control
     * characters, newlines and {@code §} codes. A colour code in an imported label can hide text or
     * forge the look of a system message, and neither is worth a label being expressive.
     */
    TEXT,

    /** A HUD element's placement: numbers only, each range-checked against the screen. */
    TRANSFORM,

    /**
     * The settings sidebar arrangement, as the one-line text {@code SidebarLayout} writes. Accepted
     * only if it decodes under that class's strict reader, and stored as the reader re-encodes it -
     * so an import can only ever set ids and short plain names.
     */
    SIDEBAR_LAYOUT
}
