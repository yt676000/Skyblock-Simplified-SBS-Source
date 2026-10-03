/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.pathfinding;

/**
 * One feature that asks the pathfinder for a route. Every source gets a route of its own, searched
 * and drawn at the same time as the others - see {@link PathfindingManager}.
 *
 * <p><b>Declaration order is priority</b>, highest first, and it is the order {@link PathRouting}
 * documents: the map click (the player's explicit, most recent choice), the Quest Guide step, the
 * scoreboard objective, then the candidate sets - secrets, Hideyho, fairy souls, commissions - and
 * the dev waypoints last. Priority no longer decides which route <i>exists</i>; it decides which one
 * is the primary (drawn on top, answered by {@link PathfindingManager#path()}), which is searched
 * first when the budget is tight, and which is paused first when the route cap is reached.
 *
 * <p>{@link #id()} is persisted as a key of the per-source colour and marker maps in the config, so
 * it must never change once shipped; add new sources with a new id rather than renaming one.
 */
public enum RouteSource {

    MAP("map", "Map", 0xFFD54F),
    QUEST("quest", "Quest", 0x66E08A),
    OBJECTIVE("objective", "Objective", 0x4FC3F7),
    SECRETS("secrets", "Secret", 0xFF6B6B),
    HIDEYHO("hideyho", "Hideyho", 0xFFA040),
    FAIRY_SOULS("fairysouls", "Fairy Soul", 0xF48FEF),
    COMMISSIONS("commissions", "Commission", 0xB388FF),
    DEV("dev", "Waypoint", 0x5B9BFF);

    private final String id;
    private final String displayName;
    private final int defaultRgb;

    RouteSource(String id, String displayName, int defaultRgb) {
        this.id = id;
        this.displayName = displayName;
        this.defaultRgb = defaultRgb;
    }

    /** Stable config key - see the class comment. */
    public String id() {
        return id;
    }

    /** The name the marker and the HUD list show, e.g. "Fairy Soul · 42m". */
    public String displayName() {
        return displayName;
    }

    /** The colour used while the player has not picked one. Distinct per source on purpose. */
    public int defaultRgb() {
        return defaultRgb;
    }

    /** Lower is more important; the declaration order. */
    public int priority() {
        return ordinal();
    }
}
