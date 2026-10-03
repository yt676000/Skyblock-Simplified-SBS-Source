/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.foraging.model;

import java.util.Locale;

/**
 * What a preset group <i>is</i>, so a feature attaches to the right kind of group rather than to
 * "any waypoint we happen to ship".
 *
 * <p><b>Why a type and not a name.</b> Two groups can share an island - Torrhus Canyon has both honey
 * trees and honey hives - so the island no longer identifies a group, and a label cannot be the
 * answer because renaming a group must never change how it is treated ({@code AGENTS.md}, Identity).
 * The lather timer binds to {@link #TREE} and can therefore never start on a hive at any matching
 * tolerance; that is a structural guarantee rather than a tuning problem.
 *
 * <p><b>It is also what splits the settings pages.</b> Two modules list preset groups - Galatea
 * Waypoints takes {@link #TREE}, {@link #HIVE} and {@link #LANDMARK}, Critter Waypoints takes
 * {@link #CRITTER} - and each asks for its kinds by name. A group therefore appears on exactly one
 * page, chosen by what it is rather than by which island it names, which is what keeps a new group a
 * data change instead of an edit to two modules.
 *
 * <p><b>{@link #UNKNOWN} is the default on purpose, and it is inert.</b> A group whose {@code kind}
 * is absent or unreadable matches no feature at all. Defaulting to {@code TREE} would have been the
 * convenient choice and would silently turn a malformed entry - or one written by a build that
 * predates a later kind - into a lather target, which is exactly what this enum exists to prevent.
 * Every consumer therefore tests for an explicit kind, never for "not something else".
 */
public enum PresetKind {

    /** A tree that can be lathered. The only kind the lather timer will ever bind to. */
    TREE,

    /** A hive harvested for honeycomb. Its own respawn state, on its own trigger. */
    HIVE,

    /** A fixed point of interest - a beacon. Carries no state and no timer of any kind. */
    LANDMARK,

    /**
     * A place a critter is known to spawn, inside the Critter Safari. Carries no state: a spawn
     * location is a landmark that happens to be named after what stands on it, and a critter is not
     * a harvest with a respawn to count down.
     */
    CRITTER,

    /** Absent, unreadable, or from a newer file than this build understands. Matches nothing. */
    UNKNOWN;

    /**
     * The kind named by a data file, or {@link #UNKNOWN} for anything this build does not recognise.
     *
     * <p>An unknown name is not an error: the schema gate already refuses a document this build
     * cannot read at all, so a name arriving here is one a <i>readable</i> file used for a group this
     * build has no feature for. Inert is the correct answer, and the group still renders - it just
     * attaches to nothing.
     */
    public static PresetKind parse(String name) {
        if (name == null || name.isBlank()) {
            return UNKNOWN;
        }
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }

    /** Whether any per-point state (a timer) may attach to a group of this kind. */
    public boolean carriesState() {
        return this == TREE || this == HIVE;
    }
}
