/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.croesus.logic;

import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.social.chat.logic.SBSChat;

/**
 * One line in your own chat, on arriving in the Dungeon Hub, if runs were left unopened.
 *
 * <p><b>Arriving in the hub is the moment, and it is a fact rather than a guess.</b> A run ends by
 * putting you back in the Dungeon Hub, which is also where Croesus stands - so "after a dungeon run"
 * and "within walking distance of the chests" are the same event, and it is one
 * {@link SkyBlockLocation} already answers. Nothing new had to be detected for this.
 *
 * <p><b>It says when it learned the number.</b> The count comes from {@link CroesusStore} and was
 * true when the menu was last open; a chest opened since, in another session or on another device,
 * is invisible to it. So the line carries the age and is worded as a memory - "when you last looked"
 * - rather than as a report of the present. A reminder that overstates what it knows is worse than
 * no reminder, because the player stops believing the next one.
 *
 * <p><b>Two guards against repeating itself</b>, because the island is read from the tab list and the
 * tab list is briefly blank across a warp. The edge alone would fire again on every blink; the
 * cooldown alone would fire again on a long stay. Together they mean one line per arrival.
 */
public final class CroesusReminder {

    private static final CroesusReminder INSTANCE = new CroesusReminder();

    /** Where Croesus stands, spelt as {@code IslandCatalog} seeds it. */
    private static final String DUNGEON_HUB = "Dungeon Hub";

    /** The shortest gap between two reminders, whatever the location does in between. */
    private static final long COOLDOWN_MS = 5 * 60_000L;

    private boolean wasInHub;
    private long lastSentAt;

    private CroesusReminder() {
    }

    public static CroesusReminder getInstance() {
        return INSTANCE;
    }

    /** Called every client tick. Inert unless the reminder is switched on. */
    public void onClientTick() {
        if (!ConfigManager.getInstance().get().dungeons.croesusReminder) {
            // Not merely a return: leaving the edge latched would fire the moment it is switched
            // back on, for an arrival that happened while the feature was off.
            wasInHub = false;
            return;
        }
        boolean inHub = SkyBlockLocation.onIsland(DUNGEON_HUB);
        if (inHub && !wasInHub) {
            announce();
        }
        wasInHub = inHub;
    }

    /** A new world is a new arrival: the next time the hub is seen, it counts. */
    public void onWorldChange() {
        wasInHub = false;
    }

    private void announce() {
        long now = System.currentTimeMillis();
        if (now - lastSentAt < COOLDOWN_MS) {
            return;
        }
        CroesusStore store = CroesusStore.getInstance();
        if (!store.known()) {
            return;   // Croesus has never been open on this profile: there is nothing remembered
        }
        int runs = store.unopenedRuns();
        if (runs <= 0) {
            return;
        }
        lastSentAt = now;
        SBSChat.send(Component.literal(runs + (runs == 1 ? " run" : " runs")
                + " still had chests at Croesus when you last looked (" + store.ageText() + ")"));
    }
}
