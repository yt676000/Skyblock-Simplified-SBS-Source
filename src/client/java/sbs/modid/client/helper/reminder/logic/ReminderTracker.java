/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.reminder.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.sounds.SoundEvents;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.alert.AlertChannel;
import sbs.modid.client.core.alert.AlertChannels;
import sbs.modid.client.core.alert.Alerts;
import sbs.modid.client.core.audio.SbsAudio;
import sbs.modid.client.helper.reminder.model.ReminderChore;
import sbs.modid.client.skills.farming.model.FarmingText;
import sbs.modid.client.social.chat.logic.SBSChat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pings you in chat when a recurring chore is due again.
 *
 * <p>Two kinds, deliberately different:
 * <ul>
 *   <li><b>Chores</b> ({@link ReminderChore}) – known SkyBlock cooldowns. The countdown starts when
 *       the chat line proving you did it shows up, so it tracks the <i>real</i> cooldown rather than
 *       a fixed schedule. Once due it stays due and repeats every
 *       {@link SBSConfig.RemindersSettings#repeatMinutes} until you actually do it – forgetting is
 *       the whole problem being solved, so one easily-missed line is not enough.</li>
 *   <li><b>Custom slots</b> – a label and an interval you type in. Purely time-based: they ping and
 *       immediately re-arm, because nothing here can know whether you did the thing.</li>
 * </ul>
 *
 * <p>Timestamps live in the config, not in memory: a 12-hour cooldown spans sessions, and one that
 * reset on every launch would never fire. Pings are held back until you are on SkyBlock and a few
 * seconds past the login flood, so a due reminder is not the line that scrolls past unread.
 */
public final class ReminderTracker {

    private static final ReminderTracker INSTANCE = new ReminderTracker();

    /** Chores move on the scale of hours; checking every few seconds is plenty. */
    private static final long CHECK_INTERVAL_MS = 5_000L;
    /** Silence right after joining a world - the login chat flood would swallow the ping. */
    private static final long JOIN_GRACE_MS = 15_000L;

    /** Reminder id -> when it was last announced, so the repeat can be paced. In memory only. */
    private final Map<String, Long> lastPinged = new ConcurrentHashMap<>();

    private long lastCheckAt;
    private long inWorldSince;

    private ReminderTracker() {
    }

    public static ReminderTracker getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.RemindersSettings cfg() {
        return ConfigManager.getInstance().get().reminders;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    // ------------------------------------------------------------------ chat

    /** Called for every chat line: a chore's "done" line restarts its cooldown. */
    public void onChat(String text) {
        if (text == null || !cfg().enabled) {
            return;
        }
        String line = FarmingText.strip(text).toLowerCase(Locale.ROOT);
        if (line.isEmpty()) {
            return;
        }
        for (ReminderChore chore : ReminderChore.BUILT_IN) {
            if (choreEnabled(chore) && chore.doneBy(line)) {
                markDone(chore);
            }
        }
    }

    /** Books a chore as done now and confirms when it comes back around. */
    public void markDone(ReminderChore chore) {
        long now = System.currentTimeMillis();
        cfg().lastDone.put(chore.id(), now);
        save();
        lastPinged.remove(chore.id());
        SBSChat.send(Component.literal(" " + chore.name() + " done  •  reminder in ")
                .withColor(SBSChat.WHITE)
                .append(Component.literal(FarmingText.duration(chore.cooldownMs()))
                        .withColor(0x57D977)));
    }

    // ------------------------------------------------------------------ tick

    /** Called every client tick; does its real work every {@link #CHECK_INTERVAL_MS}. */
    public void onClientTick() {
        SBSConfig.RemindersSettings cfg = cfg();
        if (!cfg.enabled) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            inWorldSince = 0;
            return;
        }
        long now = System.currentTimeMillis();
        if (inWorldSince == 0) {
            inWorldSince = now;
        }
        if (now - lastCheckAt < CHECK_INTERVAL_MS) {
            return;
        }
        lastCheckAt = now;
        if (now - inWorldSince < JOIN_GRACE_MS) {
            return;
        }
        // An empty zone means no SkyBlock sidebar - a lobby or limbo, where no chore is doable.
        if (SkyBlockLocation.zone().isEmpty()) {
            return;
        }

        for (ReminderChore chore : ReminderChore.BUILT_IN) {
            if (choreEnabled(chore)) {
                checkChore(cfg, chore, now);
            }
        }
        for (Slot slot : customSlots(cfg)) {
            checkSlot(cfg, slot, now);
        }
        checkHollowsLobby(cfg);
    }

    /**
     * The Crystal Hollows lobby cutoff: a ping on joining a lobby near it, one when the lead window
     * starts, one at the cutoff day. {@link HollowsLobbyReminder} decides, from the clock and lobby id
     * {@link HollowsLobbyWatch} keeps; this only delivers.
     */
    private static void checkHollowsLobby(SBSConfig.RemindersSettings cfg) {
        if (!cfg.hollowsLobbyClosing) {
            return;
        }
        HollowsLobbyWatch watch = HollowsLobbyWatch.getInstance();
        HollowsLobbyReminder.Notice notice = HollowsLobbyReminder.getInstance().check(
                watch.lobbyKey(), watch.inHollows(), watch.clockTicks(), watch.ticksPerSecond(),
                new HollowsLobbyReminder.Settings(cfg.hollowsCloseDay, cfg.hollowsWarnMinutes,
                        cfg.hollowsJoinNotice, cfg.hollowsLeadNotice, cfg.hollowsClosedNotice));
        if (notice != null) {
            ping(cfg, notice.name(), notice.note());
        }
    }

    /**
     * Announces a chore once its cooldown has run out, then again every {@code repeatMinutes} for as
     * long as it stays undone. A chore that was never seen being done has nothing to count from and
     * is skipped - the settings page has a button to seed it.
     */
    private void checkChore(SBSConfig.RemindersSettings cfg, ReminderChore chore, long now) {
        Long done = cfg.lastDone.get(chore.id());
        if (done == null) {
            return;
        }
        long dueAt = done + chore.cooldownMs();
        if (now < dueAt) {
            lastPinged.remove(chore.id());   // still on cooldown: the next ping starts fresh
            return;
        }
        Long pinged = lastPinged.get(chore.id());
        if (pinged != null) {
            long repeatMs = Math.max(0, cfg.repeatMinutes) * 60_000L;
            if (repeatMs == 0 || now - pinged < repeatMs) {
                return;
            }
        }
        lastPinged.put(chore.id(), now);
        ping(cfg, chore.name(), chore.note());
    }

    /** Announces a custom slot on its interval and re-arms it straight away. */
    private void checkSlot(SBSConfig.RemindersSettings cfg, Slot slot, long now) {
        Long done = cfg.lastDone.get(slot.id());
        if (done == null) {
            // A slot just filled in starts counting now, so it fires after one full interval
            // instead of instantly.
            cfg.lastDone.put(slot.id(), now);
            save();
            return;
        }
        if (now - done < slot.intervalMs()) {
            return;
        }
        cfg.lastDone.put(slot.id(), now);
        save();
        ping(cfg, slot.label(), "");
    }

    /**
     * The chat line itself, plus the optional ding and OS notification.
     *
     * <p>The chat line goes out either way, so it doubles as this feature's in-game fallback: when a
     * desktop notification cannot be delivered, the reminder still says so - just once more, loudly,
     * rather than only in a toast nobody saw.
     */
    private static void ping(SBSConfig.RemindersSettings cfg, String name, String note) {
        MutableComponent body = Component.literal(" Reminder: ").withColor(SBSChat.WHITE)
                .append(Component.literal(name).withColor(0xFFD65A));
        if (!note.isEmpty()) {
            body.append(Component.literal("  •  " + note).withColor(0x9AA4B2));
        }
        SBSChat.send(body);

        Minecraft minecraft = Minecraft.getInstance();
        if (cfg.sound && minecraft.player != null) {
            minecraft.player.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 1.0f, 1.4f);
        }
        // Extra channels on top of the chat line - which always goes out, so this only ever adds.
        // The chat bit is cleared: sending it here would print the same reminder twice.
        int mask = cfg.extraChannels & ~AlertChannel.CHAT.bit();
        if (cfg.sound) {
            mask &= ~AlertChannel.SOUND.bit();   // the ding above already covered it
        }
        if (AlertChannels.any(mask)) {
            String text = note.isEmpty() ? name : name + " - " + note;
            Alerts.send(new Alerts.Alert("Reminder", text, SbsAudio.Tone.CHIME, null), mask);
        }
    }

    // ------------------------------------------------------------------ state for the settings page

    /**
     * What a chore's row should say: how long until it is due, that it is due now, or that nothing
     * has been recorded yet.
     */
    public String choreStatus(ReminderChore chore) {
        Long done = cfg().lastDone.get(chore.id());
        if (done == null) {
            return "not tracked yet";
        }
        long remaining = done + chore.cooldownMs() - System.currentTimeMillis();
        return remaining <= 0 ? "due now" : "in " + FarmingText.duration(remaining);
    }

    /** Whether this chore's own toggle is on. */
    public static boolean choreEnabled(ReminderChore chore) {
        SBSConfig.RemindersSettings cfg = cfg();
        return switch (chore.id()) {
            case "hungry_hiker" -> cfg.hungryHiker;
            default -> true;
        };
    }

    /** One filled-in custom reminder slot. */
    private record Slot(String id, String label, long intervalMs) {
    }

    /** The custom slots that are actually in use - a slot with no label is off. */
    private static List<Slot> customSlots(SBSConfig.RemindersSettings cfg) {
        List<Slot> slots = new ArrayList<>(3);
        addSlot(slots, "custom1", cfg.custom1Label, cfg.custom1Minutes);
        addSlot(slots, "custom2", cfg.custom2Label, cfg.custom2Minutes);
        addSlot(slots, "custom3", cfg.custom3Label, cfg.custom3Minutes);
        return slots;
    }

    private static void addSlot(List<Slot> slots, String id, String label, int minutes) {
        if (label != null && !label.isBlank() && minutes > 0) {
            slots.add(new Slot(id, label.trim(), minutes * 60_000L));
        }
    }
}
