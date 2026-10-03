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
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.social.chat.logic.SBSChat;

import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Asks once before a typed command takes you out of a Crystal Hollows lobby that is closed to warps.
 *
 * <p>Past the cutoff day, leaving is the one slip that cannot be undone: the lobby will not take you
 * back. With this on (it is off by default), the first {@code /warp ...}, {@code /hub}, {@code /is}
 * or other leave command typed there is held and a chat line says why; sending the same command again
 * within {@link #CONFIRM_MS} lets it through.
 *
 * <p><b>Why this is allowed.</b> It sends nothing and decides nothing for the player: it only ever
 * asks for one more keystroke, and never for a second one - the repeat always passes. It confers no
 * advantage over anyone. It also <b>fails open</b>: any error while deciding lets the command go,
 * because a guard that can trap a player in a lobby is worse than no guard.
 *
 * <p>Only commands typed into chat pass through here. A warp picked in the Fast Travel menu, a warp
 * scroll or a key bound to a command is not seen.
 */
public final class HollowsLeaveGuard {

    private static final HollowsLeaveGuard INSTANCE = new HollowsLeaveGuard();

    /** How long the repeat that confirms a held command has. */
    public static final long CONFIRM_MS = 5_000L;

    /**
     * Command words that move you to another server. {@code /warp} only with a destination: bare it
     * opens the Fast Travel menu and moves nothing. {@code /party warp} is absent on purpose - it
     * brings the party to you.
     */
    static final Set<String> LEAVE_VERBS = Set.of(
            "warp", "hub", "is", "island", "garden", "visit", "lobby", "l", "play", "limbo",
            "mainlobby", "hypixel", "skyblock");

    private String held;
    private long heldAt;

    HollowsLeaveGuard() {
    }

    public static HollowsLeaveGuard getInstance() {
        return INSTANCE;
    }

    /**
     * Called from {@code ChatScreenMixin} for every line the player submits; {@code true} means hold
     * it (the caller cancels). Never throws.
     */
    public static boolean intercept(String message) {
        boolean hold = INSTANCE.holdSafely(message, HollowsLeaveGuard::armed, System.currentTimeMillis());
        if (hold) {
            try {
                SBSChat.send(Component.literal(" This Crystal Hollows lobby is closed to warps - if you "
                                + "leave, you won't get back in. ").withColor(0xFFD65A)
                        .append(Component.literal("Send " + message.strip() + " again within "
                                + CONFIRM_MS / 1000 + " s to leave anyway.").withColor(SBSChat.WHITE)));
                // The chat screen records history only for what it sends, and this was cancelled:
                // keep it so the confirming repeat is one arrow-key away.
                Minecraft.getInstance().gui.hud.getChat().addRecentChat(message);
            } catch (RuntimeException e) {
                SkyblockSimplifiedSBS.LOGGER.warn("[SBS][LobbyDay] leave guard notice failed", e);
            }
        }
        return hold;
    }

    /**
     * {@link #shouldHold} with every failure turned into "let it through". {@code armed} is asked
     * only for a leave command, so the config and location reads stay off the path of ordinary chat.
     */
    boolean holdSafely(String message, BooleanSupplier armed, long now) {
        try {
            return shouldHold(message, armed, now);
        } catch (RuntimeException e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][LobbyDay] leave guard failed, command let through", e);
            return false;
        }
    }

    /** The decision: hold a leave command once, pass its repeat within {@link #CONFIRM_MS}. */
    synchronized boolean shouldHold(String message, BooleanSupplier armed, long now) {
        String command = leaveCommand(message);
        if (command == null || !armed.getAsBoolean()) {
            return false;
        }
        if (command.equals(held) && now - heldAt <= CONFIRM_MS) {
            held = null;
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][LobbyDay] leave guard: {} confirmed", command);
            return false;
        }
        held = command;
        heldAt = now;
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][LobbyDay] leave guard: held {}", command);
        return true;
    }

    /**
     * The normalised command ("/warp hub") when {@code message} is a command that leaves the server,
     * else {@code null}.
     */
    static String leaveCommand(String message) {
        if (message == null) {
            return null;
        }
        String line = message.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (!line.startsWith("/") || line.length() < 2) {
            return null;
        }
        int space = line.indexOf(' ');
        String verb = space < 0 ? line.substring(1) : line.substring(1, space);
        if (!LEAVE_VERBS.contains(verb)) {
            return null;
        }
        if (verb.equals("warp") && space < 0) {
            return null;
        }
        return line;
    }

    /** On, on the Hollows, and the lobby past its cutoff day. */
    private static boolean armed() {
        SBSConfig.RemindersSettings cfg = ConfigManager.getInstance().get().reminders;
        if (!cfg.enabled || !cfg.hollowsLobbyClosing || !cfg.hollowsLeaveGuard
                || !SkyBlockLocation.onIsland(HollowsLobbyReminder.ISLAND)) {
            return false;
        }
        long ticks = HollowsLobbyWatch.getInstance().clockTicks();
        return HollowsLobbyReminder.stageOf(ticks, cfg.hollowsCloseDay, 0)
                == HollowsLobbyReminder.Stage.CLOSED;
    }
}
