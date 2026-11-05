/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.tick;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.perf.Perf;
import sbs.modid.client.social.chat.logic.SBSChat;

import java.util.ArrayList;
import java.util.List;

/**
 * The live {@link TickDispatcher.Meter}: the budget, the skip counter and one Perf section per task,
 * all under the task's name. Client thread only; sections never nest, so one open section suffices.
 *
 * <p>A failed task is logged with its stack trace and owes the player one chat line naming the
 * feature that was switched off; {@link #flushChat} sends it once a player exists (a failure while
 * joining a world has no chat to write to yet).
 */
final class PerfTickMeter implements TickDispatcher.Meter {

    /** Chat lines owed by failures, shared by the head and tail meters. Client thread only. */
    private static final List<String> pendingChat = new ArrayList<>(1);

    private Perf.Section open;

    @Override
    public boolean allow(TickTask task) {
        return Perf.allowTick(task.name(), task.tier());
    }

    @Override
    public void skipped(TickTask task) {
        Perf.skip(task.name());
    }

    @Override
    public void begin(TickTask task) {
        open = Perf.tick(task.name());
    }

    @Override
    public void end(TickTask task) {
        Perf.Section section = open;
        open = null;
        if (section != null) {
            section.close();
        }
    }

    @Override
    public void failed(TickTask task, Throwable thrown) {
        SkyblockSimplifiedSBS.LOGGER.error("[SBS][Tick] {} failed: {}", task.name(), thrown.toString(), thrown);
        pendingChat.add("§c" + feature(task.name()) + " was switched off after an error §7— details in the"
                + " log; it comes back when the game restarts");
    }

    /** Sends the chat lines failures left owed. Called at the start of every tick; one size check otherwise. */
    static void flushChat() {
        if (pendingChat.isEmpty() || Minecraft.getInstance().player == null) {
            return;
        }
        for (String message : pendingChat) {
            try {
                SBSChat.send(Component.literal(" " + message));
            } catch (RuntimeException | LinkageError e) {
                SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Tick] could not show a failure's chat line", e);
            }
        }
        pendingChat.clear();
    }

    /** {@code "tick.PrecisionMiningTracker"} -> {@code "PrecisionMiningTracker"}. Pure. */
    static String feature(String taskName) {
        return taskName.startsWith("tick.") ? taskName.substring(5) : taskName;
    }
}
