/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.kuudra.logic;

/**
 * Every chat line this module cares about, in one list.
 *
 * <p><b>Kuudra is a chat-driven fight.</b> The phase, the supply count, who dropped what, who is
 * fresh and what the party is calling out are all lines of text and nothing else - the scoreboard
 * carries the tier and a build percentage, and that is the entire non-chat surface. So this file is
 * effectively the module's protocol definition: if Hypixel re-words something, the fix is here.
 *
 * <p><b>Everything is behind one cheap gate.</b> The chat hook fires for every line the player ever
 * sees, including a busy hub, so the first thing that happens is a handful of {@code indexOf} calls
 * that reject the ~99.9% of lines that have nothing to do with Kuudra. Only what survives that is
 * handed to a tracker to be pattern-matched properly.
 *
 * <p>Two lines are handled <b>outside</b> the run check on purpose: the instance-transfer pair, which
 * is precisely what fires as a run is being left and would be missed by anything that first asks
 * whether a run is still live.
 */
public final class KuudraChat {

    private KuudraChat() {
    }

    /** One chat line, already free of formatting codes. Called from the shared chat hook. */
    public static void onChat(String text) {
        if (text.isEmpty()) {
            return;
        }
        // The end of a run, wherever it is seen from. Cheap enough to test first and it must not be
        // gated on anything, because it is the thing that closes a run down.
        if (text.contains("Sending to server") || text.startsWith("Starting in ")) {
            KuudraTracker.getInstance().onChat(text);
            return;
        }
        if (!KuudraTracker.getInstance().inHollow()) {
            return;
        }
        if (isState(text)) {
            KuudraTracker.getInstance().onChat(text);
        }
        if (isSupply(text)) {
            SupplyTracker.getInstance().onChat(text);
        }
        if (isFresh(text)) {
            FreshTracker.getInstance().onChat(text);
        }
        if (isAbility(text)) {
            KuudraAbilities.getInstance().onChat(text);
        }
    }

    /** Elle's dialogue, the two player-named phase lines and the two end-of-run lines. */
    private static boolean isState(String text) {
        return text.contains("[NPC] Elle:")
                || text.contains("has been eaten by Kuudra!")
                || text.contains("destroyed one of Kuudra's pods!")
                || text.contains("KUUDRA DOWN!")
                || text.equals("DEFEAT");
    }

    /** The delivery counter, both ways of losing a crate, the pickup line, and party call-outs. */
    private static boolean isSupply(String text) {
        return text.contains(" recovered")
                || text.contains("dropped Elle's supplies")
                || text.contains("slipped out of your hands")
                || text.contains("retrieved some of Elle's supplies")
                || isParty(text);
    }

    /** Hypixel's own perk line, plus anyone announcing their fresh in party chat. */
    private static boolean isFresh(String text) {
        return text.contains("Fresh Tools Perk") || isParty(text);
    }

    /** Spell casts, the mana readout, and the party echoes of both. */
    private static boolean isAbility(String text) {
        return text.contains("Casting Spell:")
                || text.contains("Extreme Focus!")
                || isParty(text);
    }

    private static boolean isParty(String text) {
        return text.startsWith("Party >");
    }
}
