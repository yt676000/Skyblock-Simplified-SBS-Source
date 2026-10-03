/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.bingo.model;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One Bingo card as it was read from the menu: its goals, when it was read, and the event it belongs
 * to. Field names are the {@code bingo.json} file format, so they are plain mutable fields for Gson.
 *
 * <p><b>The event is the capture's calendar month (UTC)</b> - {@code ESTIMATED}: a Bingo event is
 * believed to run for one month. A card from another month is a finished event and is never shown.
 */
public final class BingoCard {

    /** One goal square. {@code progress} is the raw lore line for a community goal, else null. */
    public static final class Goal {
        public String name = "";
        public boolean done;
        public boolean community;
        public String progress;

        public Goal() {
        }

        public Goal(String name, boolean done, boolean community, String progress) {
            this.name = name;
            this.done = done;
            this.community = community;
            this.progress = progress;
        }
    }

    public long capturedAt;
    /** {@code "2026-09"}: see the class comment. */
    public String event = "";
    public List<Goal> goals = new ArrayList<>();

    public BingoCard() {
    }

    public BingoCard(long capturedAt, List<Goal> goals) {
        this.capturedAt = capturedAt;
        this.event = eventOf(capturedAt);
        this.goals = new ArrayList<>(goals);
    }

    /** The event key a moment belongs to. */
    public static String eventOf(long epochMillis) {
        return YearMonth.from(Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC)).toString();
    }

    /** Whether this card belongs to the event running at {@code nowMillis}. */
    public boolean current(long nowMillis) {
        return event != null && event.equals(eventOf(nowMillis));
    }

    /**
     * Marks the goal with this name done, ignoring case and surrounding space. Returns whether a
     * goal was changed - an unknown name changes nothing, so a chat line can never invent a goal.
     */
    public boolean markDone(String goalName) {
        if (goalName == null) {
            return false;
        }
        String wanted = goalName.trim().toLowerCase(Locale.ROOT);
        for (Goal goal : goals) {
            if (!goal.done && goal.name.trim().toLowerCase(Locale.ROOT).equals(wanted)) {
                goal.done = true;
                return true;
            }
        }
        return false;
    }
}
