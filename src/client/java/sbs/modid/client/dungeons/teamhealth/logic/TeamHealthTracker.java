/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.teamhealth.logic;

import net.minecraft.client.Minecraft;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.alert.Alerts;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig.DungeonsSettings;
import sbs.modid.client.dungeons.run.logic.DungeonScoreboard;
import sbs.modid.client.dungeons.run.model.DungeonTeamClasses;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Teammate low-health warning: reads the teammate rows of the dungeon sidebar and warns when one
 * drops below the threshold.
 *
 * <p><b>Information only.</b> It never heals, casts, or writes in chat for the player - the only
 * outputs are the player's own alert channels and a red tint on the party box.
 *
 * <p>Ships off: the row format is unverified (see {@link TeamHealthParser}). Every teammate row whose
 * shape carries no readable health is logged once per run under {@code [SBS][TeamHealth]}, so one
 * run in game shows what the rows really say.
 */
public final class TeamHealthTracker {

    private static final TeamHealthTracker INSTANCE = new TeamHealthTracker();

    /** The sidebar changes on a server tick; four reads a second is plenty. */
    private static final long SCAN_MS = 250L;
    private static final int MAX_LOGGED = 16;

    private final LowHealthMonitor monitor = new LowHealthMonitor();
    private final Set<String> logged = new HashSet<>();
    private long lastScanAt;
    private boolean loggedFirstReading;

    private TeamHealthTracker() {
    }

    public static TeamHealthTracker getInstance() {
        return INSTANCE;
    }

    private static DungeonsSettings cfg() {
        return ConfigManager.getInstance().get().dungeons;
    }

    /** Called every client tick; throttled here. */
    public void onClientTick() {
        DungeonsSettings cfg = cfg();
        if (!cfg.teammateHealthWarn) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastScanAt < SCAN_MS) {
            return;
        }
        lastScanAt = now;
        var player = Minecraft.getInstance().player;
        if (player == null || !DungeonScoreboard.isInDungeon()) {
            reset();
            return;
        }
        String self = player.getGameProfile().name();
        boolean warn = !cfg.teammateHealthHealerOnly || DungeonTeamClasses.classOf(self) == 'H';
        for (String line : DungeonScoreboard.sidebarLines()) {
            TeamHealthParser.Reading reading = TeamHealthParser.parse(line);
            if (reading == null || reading.name().equalsIgnoreCase(self)) {
                continue;
            }
            note(line, reading);
            LowHealthMonitor.Warning warning = monitor.update(reading,
                    cfg.teammateHealthThreshold, cfg.teammateHealthCooldownSeconds * 1000L, now);
            if (warning != null && warn) {
                deliver(warning, cfg);
            }
        }
    }

    private static void deliver(LowHealthMonitor.Warning warning, DungeonsSettings cfg) {
        String title = warning.name() + " (" + TeamHealthParser.className(warning.dungeonClass())
                + ") low: " + warning.percent() + "%";
        Alerts.send(Alerts.Alert.of(title, "Teammate below " + cfg.teammateHealthThreshold
                + "% of the most health they had this run"), cfg.teammateHealthChannels);
    }

    /** Whether the party box for {@code name} should be tinted red right now. */
    public boolean tintLow(String name) {
        DungeonsSettings cfg = cfg();
        return cfg.teammateHealthWarn && cfg.teammateHealthTint && monitor.isLow(name);
    }

    public void reset() {
        if (!monitor.isEmpty()) {
            monitor.reset();
        }
        logged.clear();
        loggedFirstReading = false;
    }

    /** The probe: one line per run for the first readable row, one per distinct unreadable row. */
    private void note(String line, TeamHealthParser.Reading reading) {
        if (reading.state() == TeamHealthParser.State.UNKNOWN) {
            String shape = line.replaceAll("\\d", "#").toLowerCase(Locale.ROOT);
            if (logged.size() < MAX_LOGGED && logged.add(shape)) {
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][TeamHealth] teammate row with no readable "
                        + "health (format unverified): \"{}\"", line);
            }
        } else if (!loggedFirstReading) {
            loggedFirstReading = true;
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][TeamHealth] read \"{}\" as {} {}", line,
                    reading.state(), reading.health());
        }
    }
}
