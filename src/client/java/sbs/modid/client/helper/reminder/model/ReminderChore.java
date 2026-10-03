/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.reminder.model;

import java.util.List;
import java.util.Locale;

/**
 * A recurring SkyBlock chore the reminder module can watch: something you do, that then goes on a
 * cooldown, and that is easy to forget once the cooldown is up.
 *
 * <p>Each chore knows the chat line that proves you just did it. That is what makes a built-in chore
 * different from a custom reminder: the countdown starts from the <i>actual</i> action rather than
 * from a fixed interval, so it stays right whether you feed the Hiker on schedule or three hours
 * late. Nothing is ever assumed - a chore with no recorded completion stays silent instead of
 * inventing a start point (see {@code ReminderTracker}), which is why the settings page has a
 * "did it just now" button to seed it once.
 *
 * <p>Adding a chore is one entry in {@link #BUILT_IN} plus its toggle in
 * {@code SBSConfig.RemindersSettings} - the tracker iterates this list and needs no other change.
 */
public record ReminderChore(String id, String name, long cooldownMs, String note,
                            List<String> doneTriggers) {

    private static final long HOUR_MS = 3_600_000L;

    /**
     * Hungry Hiker (Mushroom Gorge / Desert Settlement): bring him the food he asks for and he is fed
     * for 144 SkyBlock days, but you may feed him again after 36 SkyBlock days - 12 real hours. Miss
     * the 144 (~48 real hours) and he perishes, which is the part worth a note in the ping.
     *
     * <p>The trigger is the middle of his thank-you line ("Thanks for the food. This should fill me
     * up for 144 SkyBlock days..."): no NPC prefix, no player name, no number that a balance patch
     * could move. Deliberately not anchored on his name either - the dialogue prefix is the part
     * Hypixel restyles, and a missed feed is a worse failure here than the odd false match, which
     * costs one press of the settings button to correct.
     */
    public static final ReminderChore HUNGRY_HIKER = new ReminderChore(
            "hungry_hiker", "Hungry Hiker", 12 * HOUR_MS,
            "he perishes ~48h after the last feed",
            List.of("should fill me up for"));

    /** Every chore the module knows, in the order their settings rows appear. */
    public static final List<ReminderChore> BUILT_IN = List.of(HUNGRY_HIKER);

    /** Whether an already-lowercased chat line proves this chore was just done. */
    public boolean doneBy(String lowercaseLine) {
        for (String trigger : doneTriggers) {
            if (lowercaseLine.contains(trigger.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
