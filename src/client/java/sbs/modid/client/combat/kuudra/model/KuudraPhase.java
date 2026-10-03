/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.kuudra.model;

import java.util.Locale;

/**
 * The stages of a Kuudra run, in the order the fight walks through them.
 *
 * <p><b>Hypixel announces every transition in chat and nowhere else.</b> There is no scoreboard line
 * that says "you are now in the build phase" - the sidebar only ever shows the objective ("Protect
 * Elle (43%)") and the tier. So the phase is a state machine driven by Elle's dialogue plus two
 * events that have no line of their own:
 * <ul>
 *   <li>{@link #BOSS} - nothing is said when Kuudra finally lands. What happens is that the arena
 *       floor drops away and you end up far below where you were fighting, so the phase is taken
 *       from the player's own Y once {@link #SKIP} or {@link #DPS} is live.</li>
 *   <li>{@link #STUN} - the line is "<i>somebody</i> has been eaten by Kuudra!", which names a
 *       player rather than being a fixed string, so it is matched with a pattern (and Elle herself
 *       is excluded - she gets eaten too, and that is not the stun).</li>
 * </ul>
 *
 * <p><b>The markers are substrings, not whole lines.</b> Elle's dialogue arrives with rank colours
 * and the {@code [NPC]} prefix, and Hypixel has re-worded parts of these lines before. Matching the
 * distinctive middle of each sentence survives a re-colour and a prefix change; matching the whole
 * line does not.
 */
public enum KuudraPhase {

    /** Not in a run at all - either outside Kuudra's Hollow or between runs. */
    NONE("None", null),

    /** Fish six supply crates out of the lava and carry them to the piles. */
    SUPPLIES("Supplies", "i will go and fish up kuudra"),

    /** Feed the supplies into the ballista piles until each one reads 100%. */
    BUILD("Build", "great work collecting my supplies"),

    /** Ballista finished; Kuudra is being reeled in and there is nothing to do but wait. */
    BALLISTA("Ballista", "the ballista is finally ready"),

    /** Someone has been swallowed and is inside the mouth, breaking a pod. */
    STUN("Stun", null),

    /** A pod is down: Kuudra is stunned and open for damage. */
    DPS("Damage", null),

    /** Elle calls the fight nearly over - the run either ends here or drops into the final phase. */
    SKIP("Skip", "i don't think he has any more in him"),

    /** The last stand on the lower arena floor, with the tile-colour slams. */
    BOSS("Boss", null),

    /** "KUUDRA DOWN!" - the kill is registered and the chests are up. */
    DOWN("Down", "kuudra down");

    private final String displayName;

    /** The lowercase fragment of Elle's line that starts this phase, or {@code null} when it has none. */
    private final String marker;

    KuudraPhase(String displayName, String marker) {
        this.displayName = displayName;
        this.marker = marker;
    }

    public String displayName() {
        return displayName;
    }

    /** Whether this is a phase inside a live run (i.e. anything that is not {@link #NONE}). */
    public boolean inRun() {
        return this != NONE;
    }

    /**
     * Whether Kuudra itself is on the field and can be shot at. Used to gate the health bar, the
     * hitbox and the direction call-out, none of which mean anything before the ballista fires.
     */
    public boolean bossUp() {
        return this == STUN || this == DPS || this == SKIP || this == BOSS;
    }

    /**
     * The phase this chat line starts, or {@code null} when it starts none. {@code line} is expected
     * colour-stripped; it is lowercased here so callers do not have to.
     */
    public static KuudraPhase fromChat(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        for (KuudraPhase phase : values()) {
            if (phase.marker != null && lower.contains(phase.marker)) {
                return phase;
            }
        }
        return null;
    }
}
