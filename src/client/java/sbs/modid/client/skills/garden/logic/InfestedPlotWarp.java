/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.garden.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.skills.garden.model.GardenPlot;
import sbs.modid.client.skills.garden.model.GardenPlotCatalog;
import sbs.modid.client.skills.garden.ui.GardenPlotsGrid;
import sbs.modid.client.social.chat.logic.SBSChat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The "take me to a pest" hotkey: one key press, one {@code /plottp} to a plot the Pests widget
 * currently lists as infested.
 *
 * <p><b>One input, one command.</b> Pressing again goes to the <i>next</i> infested plot, so the
 * plots can be walked by pressing repeatedly - but nothing is ever sent that the player did not
 * press for. There is no automatic teleport on spawn, no repeat-while-held, no queue, and nothing
 * happens after arrival. That is a deliberate boundary, not an unimplemented feature: a mod that
 * moves the player on its own is a mod that plays the game for them.
 *
 * <p><b>Where the target comes from.</b> {@link PestTracker#infestedPlots()} - the widget's own
 * {@code Plots:} list, which names every infested plot by number whether or not its pests are
 * loaded. No guessing from nearby pests, so the key either goes to a plot Hypixel called infested
 * or does nothing at all.
 *
 * <p><b>What cannot be done.</b> The widget lists a plot once however many pests sit on it, so
 * there is no per-plot count to sort by; the order is by number or by distance, both stable.
 */
public final class InfestedPlotWarp {

    /**
     * How long to wait after sending before the key will send again.
     *
     * <p>Covers Hypixel's own teleport cooldown: pressing during it would fire a command that is
     * refused, and a refused command that keeps being retried is how a client gets rate-limited.
     * A refusal seen in chat extends this - see {@link #onChat}.
     */
    private static final long SEND_COOLDOWN_MS = 3_000L;

    /** The cooldown applied when Hypixel says the teleport was refused. */
    private static final long REFUSED_COOLDOWN_MS = 5_000L;

    /** The plot the last press targeted, so the next press moves on rather than repeating it. */
    private static int lastTarget = -1;

    private static long lastSendAt;
    private static long blockedUntil;

    private InfestedPlotWarp() {
    }

    private static SBSConfig.GardenPlotsSettings cfg() {
        return ConfigManager.getInstance().get().gardenPlots;
    }

    /**
     * Handles one key press.
     *
     * <p>Called from the keybind dispatch, which already guarantees a fresh press with no screen and
     * no text field focused - so nothing here has to re-check that.
     */
    public static void onKeyPressed() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return;
        }
        // Off the Garden the key does nothing at all - silently, because a message every time you
        // press a Garden key in the Hub is noise, not help.
        if (!GardenBlueprintManager.inGarden() && !PestTracker.getInstance().onGarden()) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now < blockedUntil) {
            say("§7Teleport is on cooldown for another "
                    + Math.max(1, (blockedUntil - now + 999) / 1000) + "s");
            return;
        }
        if (now - lastSendAt < SEND_COOLDOWN_MS) {
            return;   // a double tap, not a second request - stay quiet
        }

        List<Integer> plots = ordered(player);
        if (plots.isEmpty()) {
            say("§7No infested plots right now");
            return;
        }
        int target = next(plots, player);
        if (target <= 0) {
            return;
        }
        lastTarget = target;
        lastSendAt = now;
        // Reuses the plot map's own send path: it closes the open screen first, because Hypixel
        // refuses to warp while a menu is up.
        GardenPlotsGrid.run(cfg().plotTeleportCommand + " " + target);
    }

    /**
     * The infested plots in the configured order.
     *
     * <p>Both orders are stable, which is what makes "press again for the next one" mean anything:
     * by plot number, or by distance from the player - and distance is measured to the plot's
     * centre, a fixed point, not to a pest that moves.
     */
    private static List<Integer> ordered(LocalPlayer player) {
        Set<Integer> infested = PestTracker.getInstance().infestedPlots();
        List<Integer> plots = new ArrayList<>(infested.size());
        for (int number : infested) {
            if (GardenPlotCatalog.cellOf(number) != null) {
                plots.add(number);   // drop anything that is not a real plot number
            }
        }
        if (cfg().infestedNearestFirst) {
            plots.sort((a, b) -> Double.compare(distanceTo(player, a), distanceTo(player, b)));
        } else {
            plots.sort(Integer::compareTo);
        }
        return plots;
    }

    /** Squared distance from the player to a plot's centre, for the nearest-first order. */
    private static double distanceTo(LocalPlayer player, int number) {
        int[] cell = GardenPlotCatalog.cellOf(number);
        if (cell == null) {
            return Double.MAX_VALUE;
        }
        double centreX = GardenPlot.minCorner(cell[0]) + GardenPlot.PLOT_SIZE / 2.0;
        double centreZ = GardenPlot.minCorner(cell[1]) + GardenPlot.PLOT_SIZE / 2.0;
        double dx = centreX - player.getX();
        double dz = centreZ - player.getZ();
        return dx * dx + dz * dz;
    }

    /**
     * The plot this press should go to: the one after the last target, wrapping round.
     *
     * <p>Standing on an infested plot counts as having already been sent there, so the key moves on
     * instead of teleporting you to where you are - which is the case the player actually hits when
     * they clear one plot and want the next.
     */
    private static int next(List<Integer> plots, LocalPlayer player) {
        int current = currentPlot(player);
        int anchor = plots.indexOf(lastTarget);
        if (anchor < 0) {
            anchor = plots.indexOf(current);   // no memory of a target: start from where we stand
        }
        if (anchor < 0) {
            return plots.get(0);
        }
        for (int step = 1; step <= plots.size(); step++) {
            int candidate = plots.get((anchor + step) % plots.size());
            if (candidate != current || plots.size() == 1) {
                return candidate;
            }
        }
        return plots.get(0);
    }

    /** The plot number the player is standing on, or {@code -1} when that is not a plot. */
    private static int currentPlot(LocalPlayer player) {
        GardenPlot.Bounds bounds = GardenPlot.at(player.getX(), player.getZ());
        return GardenPlotCatalog.numberAt(bounds.cellX(), bounds.cellZ());
    }

    /**
     * Watches chat for Hypixel refusing the teleport, so the next press says so instead of firing
     * another command into a cooldown that is still running.
     */
    public static void onChat(String text) {
        if (text == null || lastSendAt == 0
                || System.currentTimeMillis() - lastSendAt > SEND_COOLDOWN_MS) {
            return;   // only a line right after our own send can be about it
        }
        String lower = text.toLowerCase(Locale.ROOT);
        boolean refused = lower.contains("you can't teleport") || lower.contains("cannot teleport")
                || lower.contains("wait a moment") || lower.contains("slow down")
                || (lower.contains("plot") && lower.contains("not unlocked"));
        if (refused) {
            blockedUntil = System.currentTimeMillis() + REFUSED_COOLDOWN_MS;
            lastTarget = -1;   // that target never happened; do not skip it next press
        }
    }

    /** Clears the walk between worlds, so a new session starts at the first plot again. */
    public static void reset() {
        lastTarget = -1;
        lastSendAt = 0;
        blockedUntil = 0;
    }

    private static void say(String text) {
        SBSChat.send(Component.literal(" " + text));
    }
}
