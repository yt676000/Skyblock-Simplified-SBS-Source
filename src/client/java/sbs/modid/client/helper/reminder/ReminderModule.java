/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.reminder;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.ModuleSubgroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.helper.reminder.logic.HollowsLobbyReminder;
import sbs.modid.client.helper.reminder.logic.HollowsLobbyWatch;
import sbs.modid.client.helper.reminder.logic.ReminderTracker;
import sbs.modid.client.helper.reminder.model.ReminderChore;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.ArrayList;
import java.util.List;

/**
 * Reminders module (Quality of Life): chat pings for the recurring chores that are easy to forget -
 * the Hungry Hiker's 12-hour feed, plus three free slots for anything else on a fixed interval.
 *
 * <p>The counting, chat detection and pings live in {@link ReminderTracker}; this class only
 * registers the module and its rows. Self-registered via
 * {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 */
public final class ReminderModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public ReminderModule() {
    }

    @Override
    public String id() {
        return "reminders";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.QUALITY_OF_LIFE;
    }

    @Override
    public ModuleSubgroup subgroup() {
        return ModuleSubgroup.TIMERS;
    }

    @Override
    public String displayName() {
        return "Reminders";
    }

    @Override
    public String description() {
        return "Chat pings for chores on a cooldown - feed the Hungry Hiker, plus your own";
    }

    @Override
    public int accentColor() {
        return 0xFFD9A657;
    }

    private static SBSConfig.RemindersSettings cfg() {
        return ConfigManager.getInstance().get().reminders;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        List<SettingRow> rows = new ArrayList<>();
        rows.add(SettingRow.toggle("Reminders", () -> cfg().enabled,
                () -> { cfg().enabled = !cfg().enabled; save(); })
                .describe("Pings you in chat when something you do on a cooldown is ready "
                        + "again. Only ever while you are on SkyBlock."));
        rows.add(SettingRow.label("Chat pings for the chores that are easy to forget"));

        addChoreRows(rows);
        addHollowsLobbyRows(rows);

        rows.add(SettingRow.toggle("Sound", () -> cfg().sound,
                () -> { cfg().sound = !cfg().sound; save(); })
                .describe("A ding with each reminder, so it lands even when chat is busy."));
        rows.add(SettingRow.label("— Extra ways to be told, on top of the chat line —"));
        rows.addAll(sbs.modid.client.core.alert.AlertChannelRows.forAlert(
                "reminder_due", "a reminder comes due",
                () -> cfg().extraChannels,
                value -> { cfg().extraChannels = value; save(); }));
        rows.add(SettingRow.intField("Repeat Every", 0, 240,
                () -> cfg().repeatMinutes,
                value -> { cfg().repeatMinutes = value; save(); }, "min")
                .describe("How often a chore reminder says it again while it is still not done. "
                        + "0 tells you once - which is how you forget it again."));
        rows.add(SettingRow.label("Repeat applies to the chores above, not the custom slots"));

        addCustomSlot(rows, 1, () -> cfg().custom1Label, v -> cfg().custom1Label = v,
                () -> cfg().custom1Minutes, v -> cfg().custom1Minutes = v);
        addCustomSlot(rows, 2, () -> cfg().custom2Label, v -> cfg().custom2Label = v,
                () -> cfg().custom2Minutes, v -> cfg().custom2Minutes = v);
        addCustomSlot(rows, 3, () -> cfg().custom3Label, v -> cfg().custom3Label = v,
                () -> cfg().custom3Minutes, v -> cfg().custom3Minutes = v);
        rows.add(SettingRow.label("A custom slot repeats on its interval; empty text turns it off"));
        return List.copyOf(rows);
    }

    /**
     * Per chore: its toggle, a status snapshot, and the button that seeds the cooldown.
     *
     * <p>The button matters more than it looks. A chore is normally booked from its chat line, so a
     * cooldown that started before the module existed - or during a session where the line was
     * missed - is simply unknown, and the tracker stays quiet rather than guessing. Pressing this
     * once after doing the chore gets it counting.
     */
    private static void addChoreRows(List<SettingRow> rows) {
        ReminderTracker tracker = ReminderTracker.getInstance();
        for (ReminderChore chore : ReminderChore.BUILT_IN) {
            rows.add(SettingRow.toggle(chore.name(), () -> ReminderTracker.choreEnabled(chore),
                    () -> { toggleChore(chore); save(); })
                    .describe("Ping when the " + chore.name() + " cooldown is up. The timer starts "
                            + "from the chat line you get for doing it, so it follows the real "
                            + "cooldown - " + chore.note() + "."));
            rows.add(SettingRow.button(chore.name() + ": did it just now",
                    () -> tracker.markDone(chore))
                    .describe("Starts the cooldown from this moment. Use it once to get a chore "
                            + "counting that you did before the reminder was watching."));
            rows.add(SettingRow.label(chore.name() + ": " + tracker.choreStatus(chore)));
        }
    }

    /**
     * The Crystal Hollows lobby cutoff: its toggle, the lead window, the day itself, one switch per
     * notice, the card, the leave guard and where the current lobby stands. The day is a typed field
     * because the exact value is the whole setting.
     */
    private static void addHollowsLobbyRows(List<SettingRow> rows) {
        rows.add(SettingRow.label("— Crystal Hollows Lobby Closing —"));
        rows.add(SettingRow.toggle("Crystal Hollows Lobby Closing", () -> cfg().hollowsLobbyClosing,
                () -> { cfg().hollowsLobbyClosing = !cfg().hollowsLobbyClosing; save(); })
                .describe("From day " + cfg().hollowsCloseDay + " of a Crystal Hollows lobby nobody "
                        + "can warp into it any more - not your party, and not you once you leave. "
                        + "Tells you when you join a lobby that is close to it, when the warning "
                        + "time starts and when the day comes, once each per lobby, so you can still "
                        + "bring everyone in. The day is the lobby's own clock, the number F3 shows. "
                        + "Default: on.")
                .anchor("reminder_hollows_lobby"));
        rows.add(SettingRow.rangeSlider("Warn Before It Closes", 0, 60,
                () -> cfg().hollowsWarnMinutes,
                value -> { cfg().hollowsWarnMinutes = value; save(); }, "min")
                .describe("How far ahead of the cutoff the warning time starts, in minutes of the "
                        + "lobby's clock: 20 minutes is one Minecraft day, so the default warns from "
                        + "day 18.0. A lagging lobby makes that take longer in real time - the "
                        + "warning itself says how long it really is. 0 only tells you on the day. "
                        + "Default: 20 min.")
                .anchor("reminder_hollows_warn"));
        rows.add(SettingRow.intField("Closes On Day", 1, 60,
                () -> cfg().hollowsCloseDay,
                value -> { cfg().hollowsCloseDay = value; save(); }, "")
                .describe("The first lobby day on which warps into the Crystal Hollows are refused. "
                        + "Not timed by SBS - change it if Hypixel moves the cutoff. Default: 19.")
                .anchor("reminder_hollows_day"));
        rows.add(SettingRow.toggle("Tell Me On Joining", () -> cfg().hollowsJoinNotice,
                () -> { cfg().hollowsJoinNotice = !cfg().hollowsJoinNotice; save(); })
                .describe("Joining a lobby that is already inside the warning time tells you its "
                        + "day and how long it has left. Default: on.")
                .anchor("reminder_hollows_join"));
        rows.add(SettingRow.toggle("Tell Me When Warning Starts", () -> cfg().hollowsLeadNotice,
                () -> { cfg().hollowsLeadNotice = !cfg().hollowsLeadNotice; save(); })
                .describe("While you are in the lobby, tells you when the warning time starts and "
                        + "how many real minutes are left. Default: on.")
                .anchor("reminder_hollows_lead"));
        rows.add(SettingRow.toggle("Tell Me On The Day", () -> cfg().hollowsClosedNotice,
                () -> { cfg().hollowsClosedNotice = !cfg().hollowsClosedNotice; save(); })
                .describe("Tells you when the lobby reaches the closing day: from then on, leaving "
                        + "means not getting back in. Default: on.")
                .anchor("reminder_hollows_closed"));
        rows.add(SettingRow.toggle("Lobby Day Card", () -> cfg().hollowsHud,
                () -> { cfg().hollowsHud = !cfg().hollowsHud; save(); })
                .describe("A small card on the Crystal Hollows: the lobby day (the number F3 "
                        + "shows) and how long until it closes to warps, turning yellow, then orange "
                        + "through the warning time and red on the day. Default: on.")
                .anchor("reminder_hollows_hud"));
        rows.add(SettingRow.button("Move / Resize Lobby Day Card", () -> net.minecraft.client.Minecraft
                        .getInstance().setScreenAndShow(new sbs.modid.client.ui.hud.edit.ui.HudEditorScreen(
                                new sbs.modid.client.ui.hud.edit.model.HudElement[] {
                                        sbs.modid.client.ui.hud.edit.model.HudElement.LOBBY_DAY},
                                "Edit Lobby Day Card")))
                .describe("Opens the editor where you drag the card anywhere on the screen."));
        rows.add(SettingRow.toggle("Ask Before Leaving A Closed Lobby", () -> cfg().hollowsLeaveGuard,
                () -> { cfg().hollowsLeaveGuard = !cfg().hollowsLeaveGuard; save(); })
                .describe("Once the lobby has reached the closing day, a typed /warp, /hub, /is, "
                        + "/lobby or similar is held once with a note in chat - send it again within "
                        + "5 seconds and it goes through. It never stops the second try, and does "
                        + "not see warps picked from a menu. Default: off.")
                .anchor("reminder_hollows_leave_guard"));
        HollowsLobbyWatch watch = HollowsLobbyWatch.getInstance();
        rows.add(SettingRow.label("Crystal Hollows: " + HollowsLobbyReminder.status(
                watch.inHollows(), watch.clockTicks(), watch.ticksPerSecond(),
                cfg().hollowsCloseDay)));
    }

    /** Flips the one chore toggle. A switch rather than a map, so the config keys stay readable. */
    private static void toggleChore(ReminderChore chore) {
        if (chore.id().equals(ReminderChore.HUNGRY_HIKER.id())) {
            cfg().hungryHiker = !cfg().hungryHiker;
        }
    }

    private static void addCustomSlot(List<SettingRow> rows, int index,
                                      java.util.function.Supplier<String> label,
                                      java.util.function.Consumer<String> setLabel,
                                      java.util.function.IntSupplier minutes,
                                      java.util.function.IntConsumer setMinutes) {
        rows.add(SettingRow.text("Reminder " + index, "what to remind you of", 48,
                label, value -> { setLabel.accept(value.trim()); save(); })
                .describe("Free reminder slot: the text is pinged into chat on the interval "
                        + "below. Leave it empty to turn the slot off."));
        rows.add(SettingRow.intField("Reminder " + index + " Every", 1, 1440,
                minutes, value -> { setMinutes.accept(value); save(); }, "min")
                .describe("How often that slot repeats, in minutes (up to a full day)."));
    }
}
