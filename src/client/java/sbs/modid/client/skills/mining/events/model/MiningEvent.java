/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.events.model;

import java.util.Locale;

/**
 * The Dwarven Mines / Crystal Hollows lobby events, as the game names them in chat.
 *
 * <p>Every name here is taken from the play instance's logs (2024-03-26 -> 2025-12-13), from the
 * {@code <NAME> STARTED!} header and the {@code The <Name> event starts in 20 seconds!} line. An
 * event the game announces under any other name is {@link #UNKNOWN} and keeps its raw name beside
 * this value - never dropped. The constant names are persisted ids: never rename one.
 */
public enum MiningEvent {

    TWO_X_POWDER("2x Powder", Kind.PASSIVE),
    GONE_WITH_THE_WIND("Gone with the Wind", Kind.PASSIVE),
    BETTER_TOGETHER("Better Together", Kind.PASSIVE),
    GOBLIN_RAID("Goblin Raid", Kind.OBJECTIVE),
    RAFFLE("Raffle", Kind.OBJECTIVE),
    MITHRIL_GOURMAND("Mithril Gourmand", Kind.OBJECTIVE),
    /** A name not listed above. The raw name travels with it. */
    UNKNOWN("Unknown event", Kind.PASSIVE);

    /**
     * The subtitle line under the header. {@code Passive Active Event} events run their full time;
     * {@code Mining Event} events have a goal and end as soon as it is met (Goblin Raid 144-309 s,
     * Mithril Gourmand 223-621 s in the logs), so a median remaining time would be wrong for them.
     */
    public enum Kind {
        PASSIVE,
        OBJECTIVE
    }

    private final String displayName;
    private final Kind kind;

    MiningEvent(String displayName, Kind kind) {
        this.displayName = displayName;
        this.kind = kind;
    }

    public String displayName() {
        return displayName;
    }

    public Kind kind() {
        return kind;
    }

    /** Whether the event can end before its full time - an estimate then reads "up to". */
    public boolean endsEarly() {
        return kind == Kind.OBJECTIVE;
    }

    /**
     * The event a name refers to, in any capitalisation ({@code 2X POWDER}, {@code 2x Powder}), or
     * {@link #UNKNOWN}. Never {@code null}.
     */
    public static MiningEvent byName(String name) {
        if (name == null) {
            return UNKNOWN;
        }
        String wanted = name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        for (MiningEvent event : values()) {
            if (event != UNKNOWN && event.displayName.toLowerCase(Locale.ROOT).equals(wanted)) {
                return event;
            }
        }
        return UNKNOWN;
    }

    /** The stored id back to a value; anything unreadable is {@link #UNKNOWN}. */
    public static MiningEvent byId(String id) {
        if (id == null) {
            return UNKNOWN;
        }
        try {
            return valueOf(id);
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }

    /** What to print for an event: the known name, or the raw one for {@link #UNKNOWN}. */
    public static String label(MiningEvent event, String rawName) {
        if (event == null || event == UNKNOWN) {
            return rawName == null || rawName.isBlank() ? UNKNOWN.displayName : rawName;
        }
        return event.displayName;
    }
}
