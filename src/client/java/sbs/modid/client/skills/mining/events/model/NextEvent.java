/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.events.model;

/**
 * When the next event in this lobby starts, and how sure that is. The confidence is always shown
 * with the time: a window the player cannot tell from a fact is worse than "unknown".
 *
 * @param confidence where the answer comes from
 * @param earliest   start of the window (p25), or the exact start for {@link Confidence#KNOWN}
 * @param latest     end of the window (p75); equals {@link #earliest} for {@code KNOWN}
 * @param event      the event, only when the game named it ({@code KNOWN}); otherwise {@code null}
 * @param rawName    its raw name, for an {@link MiningEvent#UNKNOWN} event
 * @param samples    how many learned intervals the window rests on ({@code 0} for {@code KNOWN})
 */
public record NextEvent(Confidence confidence, long earliest, long latest, MiningEvent event,
                        String rawName, int samples) {

    /** The labels shown on the card, verbatim. */
    public enum Confidence {
        /** The game announced it ({@code The X event starts in 20 seconds!}). */
        KNOWN("KNOWN", "§a"),
        /** Learned locally from this island's history. */
        ESTIMATED("ESTIMATED", "§e"),
        /** From other players' reports for this lobby. Not built yet - see the spec. */
        SHARED("SHARED", "§b"),
        /** Nothing supports a time. */
        UNKNOWN("UNKNOWN", "§8");

        private final String label;
        private final String colorCode;

        Confidence(String label, String colorCode) {
            this.label = label;
            this.colorCode = colorCode;
        }

        public String label() {
            return label;
        }

        public String colorCode() {
            return colorCode;
        }
    }

    public static NextEvent unknown(int samples) {
        return new NextEvent(Confidence.UNKNOWN, 0L, 0L, null, null, samples);
    }

    public boolean known() {
        return confidence != Confidence.UNKNOWN;
    }
}
