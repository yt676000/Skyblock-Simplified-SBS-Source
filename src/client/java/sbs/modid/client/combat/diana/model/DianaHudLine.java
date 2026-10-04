/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.diana.model;

import java.util.Locale;

/**
 * Every line the two Diana cards can show, in the order a fresh layout lists them.
 *
 * <h2>The constant name is the stored id</h2>
 *
 * <p>{@code config.diana.appearance} keeps each card as a list of these names, so a constant is
 * never renamed: a new line is <b>appended</b>, hidden by default, and an old config simply does not
 * list it. An id this build does not know is dropped on read rather than failing the card.
 *
 * <h2>Where each came from</h2>
 *
 * <p>{@link #legacySwitch} names the switch that used to decide whether this line was drawn. The
 * one-time migration and the four alias toggles on the Diana page key on it. A line with no switch
 * is new, and stays hidden until the player places it.
 */
public enum DianaHudLine {
    CHAINS("Chains", DianaPanel.TRACKER, LegacySwitch.CHAINS_CARD),
    OLDEST_CHAIN("Oldest Chain", DianaPanel.TRACKER, LegacySwitch.CHAINS_CARD),
    BURROWS("Burrows", DianaPanel.TRACKER, LegacySwitch.SESSION_CARD),
    CREATURES("Creatures", DianaPanel.TRACKER, LegacySwitch.SESSION_CARD),
    TREASURES("Treasures", DianaPanel.TRACKER, LegacySwitch.SESSION_CARD),
    SINCE_INQUISITOR("Since Inquisitor", DianaPanel.TRACKER, LegacySwitch.SESSION_CARD),
    /** One entry for the whole block: one row per creature type spawned this session. */
    PER_CREATURE("Per-Creature Counts", DianaPanel.TRACKER, LegacySwitch.SESSION_CARD),
    CREATURE_HEALTH("Creature Health", DianaPanel.CREATURES, LegacySwitch.CREATURE_CARD),
    NO_SHURIKEN("No Shuriken", DianaPanel.CREATURES, LegacySwitch.SHURIKEN),
    /** Added with the appearance settings; the tracker already counted it. */
    SINCE_KING("Since King", DianaPanel.TRACKER, null);

    /** The four switches that decided the cards' contents before lines could be arranged. */
    public enum LegacySwitch {
        CHAINS_CARD,
        SESSION_CARD,
        CREATURE_CARD,
        SHURIKEN
    }

    private final String displayName;
    private final DianaPanel defaultPanel;
    private final LegacySwitch legacySwitch;

    DianaHudLine(String displayName, DianaPanel defaultPanel, LegacySwitch legacySwitch) {
        this.displayName = displayName;
        this.defaultPanel = defaultPanel;
        this.legacySwitch = legacySwitch;
    }

    public String displayName() {
        return displayName;
    }

    /** Where the line goes when nothing says otherwise, and where an alias toggle puts it back. */
    public DianaPanel defaultPanel() {
        return defaultPanel;
    }

    /** The old switch this line answered to, or {@code null} for a line added since. */
    public LegacySwitch legacySwitch() {
        return legacySwitch;
    }

    /** The stored id. */
    public String id() {
        return name();
    }

    /** The constant for a stored id, or {@code null} for one this build does not know. */
    public static DianaHudLine byId(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            return valueOf(id.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }
}
