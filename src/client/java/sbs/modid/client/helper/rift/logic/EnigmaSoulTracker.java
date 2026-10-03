/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.rift.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.dungeons.events.ChatPatternRegistry;
import sbs.modid.client.helper.rift.model.EnigmaSoul;
import sbs.modid.client.helper.rift.model.SoulState;
import sbs.modid.client.social.chat.logic.SBSChat;

import java.util.regex.Matcher;

/**
 * Watches for a soul being collected and keeps the three-state picture up to date.
 *
 * <p><b>Hypixel hands over both halves in one line.</b> The collection message is "You have found
 * 31/52 Souls in the Rift Dimension!" - a count <i>and</i> its denominator, on every single pickup.
 * That is why this feature does not have the Fairy Souls' reconciliation problem: there is nothing to
 * infer about how many have been found, only about <i>which</i>.
 *
 * <p><b>Which is answered by position, and only when it is unambiguous.</b> On seeing the line, the
 * player is standing at the soul they just collected, so the one catalogued coordinate within reach
 * is it. Two coordinates within reach means no answer at all rather than the nearer one - the Cake
 * House has a soul on each floor, and guessing there would permanently hide one of them. A pickup
 * that cannot be attributed still moves the count, so the soul simply joins the unknown set, which
 * is exactly what that set is for.
 *
 * <p>Registered with {@link ChatPatternRegistry} once at construction; the registry is global, not
 * dungeon-only, despite where it lives.
 */
public final class EnigmaSoulTracker {

    private static final EnigmaSoulTracker INSTANCE = new EnigmaSoulTracker();

    /**
     * The collection line. Kept loose around the wording and strict about the shape: the two numbers
     * separated by a slash next to the word "Soul" are the part that carries meaning, and Hypixel has
     * reworded the sentence around them before.
     */
    private static final String COLLECT_PATTERN =
            "(?i)you have found\\s+(\\d+)\\s*/\\s*(\\d+)\\s+souls?";

    /**
     * How far from a catalogued coordinate the player must be for it to count as the soul they just
     * picked up. Generous enough for the ones collected from a distance (a balloon popped with the
     * blowgun, a soul that drops to the floor), tight enough that two catalogued souls are rarely
     * both in range - and when they are, the sole-match rule declines rather than guesses.
     */
    private static final double ATTRIBUTION_RADIUS = 12.0;

    private volatile boolean registered;

    private EnigmaSoulTracker() {
    }

    public static EnigmaSoulTracker getInstance() {
        return INSTANCE;
    }

    /** Registers the chat pattern. Idempotent, because the registry has no unregister. */
    public void register() {
        if (registered) {
            return;
        }
        registered = true;
        ChatPatternRegistry.getInstance().register(COLLECT_PATTERN, this::onCollected,
                "rift-enigma-soul");
    }

    private void onCollected(Matcher matcher) {
        int found;
        int total;
        try {
            found = Integer.parseInt(matcher.group(1));
            total = Integer.parseInt(matcher.group(2));
        } catch (NumberFormatException e) {
            return;
        }
        EnigmaSoulStore store = EnigmaSoulStore.getInstance();
        store.recordCount(found, total);

        EnigmaSoul soul = attribute();
        if (soul == null) {
            SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][Rift] soul {}/{} collected, no single catalogued coordinate in reach",
                    found, total);
            if (ConfigManager.getInstance().get().enigmaSouls.reportUnmatched) {
                SBSChat.send(net.minecraft.network.chat.Component.literal(
                                " Soul " + found + "/" + total
                                        + " counted, but it matched no catalogued coordinate.")
                        .withColor(SBSChat.WHITE));
            }
            return;
        }
        if (store.markCollected(soul.id)) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Rift] recorded {} ({}/{})",
                    soul.summary(), found, total);
        }
    }

    /** The one catalogued soul the player is standing at, or {@code null} when it is not unambiguous. */
    private EnigmaSoul attribute() {
        Player player = Minecraft.getInstance().player;
        if (player == null || !RiftState.getInstance().inRift()) {
            return null;
        }
        return EnigmaSoulDatabase.soleNear(player.getX(), player.getY(), player.getZ(),
                ATTRIBUTION_RADIUS);
    }

    // ------------------------------------------------------------------ the three-state view

    /** What is known about one soul right now. */
    public SoulState stateOf(EnigmaSoul soul) {
        if (soul == null) {
            return SoulState.UNKNOWN;
        }
        EnigmaSoulStore store = EnigmaSoulStore.getInstance();
        if (store.isCollected(soul.id)) {
            return SoulState.FOUND;
        }
        // Not on record. Whether that means "still out there" depends entirely on whether the record
        // accounts for everything Hypixel says has been found.
        return store.recordComplete() ? SoulState.MISSING : SoulState.UNKNOWN;
    }

    /**
     * One line explaining how much of the picture is real, for the settings page.
     *
     * <p>Its own method because this is the sentence that stops the feature being quietly wrong on an
     * established profile: a player who has 40 souls and sees 40 markers needs to be told those
     * markers are "not known" rather than "not collected".
     */
    public String reconciliation() {
        EnigmaSoulStore store = EnigmaSoulStore.getInstance();
        int reported = store.reportedFound();
        int tracked = store.trackedCount();
        int total = store.reportedTotal() > 0
                ? store.reportedTotal() : EnigmaSoulDatabase.totalInGame();

        if (reported < 0) {
            return tracked == 0
                    ? "No count seen yet - collect one soul and Hypixel states the total."
                    : tracked + " on record; no count seen yet, so nothing is confirmed missing.";
        }
        if (tracked >= reported) {
            return "Record complete: " + reported + " of " + total
                    + " found, all of them identified.";
        }
        return reported + " of " + total + " found, " + tracked + " identified - the other "
                + (reported - tracked) + " are marked unknown, not missing.";
    }

    /**
     * Whether the data file has caught up with Hypixel's own denominator.
     *
     * <p>The chat line's total is the real number of souls in the game, so a data file whose
     * {@code totalSouls} is smaller is simply out of date - and saying so is more useful than
     * letting the counts quietly disagree.
     */
    public int uncataloguedCount() {
        int reportedTotal = EnigmaSoulStore.getInstance().reportedTotal();
        if (reportedTotal <= 0) {
            return 0;
        }
        return Math.max(0, reportedTotal - EnigmaSoulDatabase.all().size());
    }
}
