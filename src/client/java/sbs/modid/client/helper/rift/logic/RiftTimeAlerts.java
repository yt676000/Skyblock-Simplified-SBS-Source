/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.rift.logic;

import sbs.modid.client.core.alert.Alerts;
import sbs.modid.client.core.audio.SbsAudio;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.helper.rift.model.RiftData;

/**
 * Turns the Rift clock into the two things worth being interrupted for: running low, and walking
 * into somewhere you cannot afford.
 *
 * <p><b>Every alert fires once per crossing, not once per tick.</b> The clock is re-read several
 * times a second, so a naive "remaining below the threshold" test is a hundred alerts a minute. Each
 * threshold therefore latches when it is crossed downwards and only re-arms when the clock goes back
 * above it - which the mote orbs make a real occurrence, not a theoretical one.
 *
 * <p><b>The area warning is about leaving, not entering.</b> Nothing in the Rift charges an entry
 * fee, so there is nothing to warn about at a door in the way the request assumed. What there is:
 * areas that will not admit you below a minimum, and areas far enough in that the clock can run out
 * while you are still walking back. Both are warned about; both are read from the data file, so
 * neither is a hard-coded guess about a place that may be re-laid out next update.
 */
public final class RiftTimeAlerts {

    private static final RiftTimeAlerts INSTANCE = new RiftTimeAlerts();

    /**
     * How much slack to add to an area's walk-out estimate before warning. The estimate is
     * hand-entered and approximate, and a warning that arrives exactly as the last second is spent
     * is a warning that arrived too late to act on.
     */
    private static final int EXIT_MARGIN_SECONDS = 20;

    /** Latches, so each threshold speaks once per crossing. */
    private boolean warnFired;
    private boolean criticalFired;
    private boolean exitFired;

    /** The zone the minimum-time warning last fired for, so it re-arms on moving somewhere else. */
    private String minimumWarnedZone = "";

    private RiftTimeAlerts() {
        RiftState.getInstance().register(new RiftState.Listener() {
            @Override
            public void onRiftEnter() {
                reset();
            }

            @Override
            public void onRiftExit() {
                reset();
            }
        });
    }

    public static RiftTimeAlerts getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.RiftTimeSettings cfg() {
        return ConfigManager.getInstance().get().riftTime;
    }

    /** Called from the client tick while in the Rift. */
    public void tick() {
        SBSConfig.RiftTimeSettings cfg = cfg();
        if (!cfg.enabled || !RiftState.getInstance().inRift()) {
            return;
        }
        RiftTime time = RiftTime.getInstance();
        if (!time.available()) {
            return;
        }
        int remaining = time.remaining();
        checkThresholds(cfg, remaining);
        checkArea(cfg, remaining);
    }

    /** The two low-time thresholds, each latched on its own crossing. */
    private void checkThresholds(SBSConfig.RiftTimeSettings cfg, int remaining) {
        // The critical one is tested first and suppresses the warning one: crossing straight past
        // both in a single reading (a hop, a slow tick) should say the worse thing, not both.
        if (cfg.criticalSeconds > 0 && remaining <= cfg.criticalSeconds) {
            if (!criticalFired) {
                criticalFired = true;
                warnFired = true;
                send(cfg, "Rift Time critical",
                        RiftTime.format(remaining) + " left - head for the Wizard Tower",
                        SbsAudio.Tone.ALARM);
            }
            return;
        }
        criticalFired = criticalFired && remaining <= cfg.criticalSeconds;

        if (cfg.warnSeconds > 0 && remaining <= cfg.warnSeconds) {
            if (!warnFired) {
                warnFired = true;
                send(cfg, "Rift Time low", RiftTime.format(remaining) + " left",
                        SbsAudio.Tone.CHIME);
            }
            return;
        }
        warnFired = false;
    }

    /**
     * The place-specific warnings: an area that will not let you in on what you are holding, and an
     * area you are already in that you may not have the time to walk out of.
     */
    private void checkArea(SBSConfig.RiftTimeSettings cfg, int remaining) {
        if (!cfg.areaWarnings) {
            return;
        }
        RiftData.Area area = RiftAreas.current();
        String zone = SkyBlockLocation.zone();

        // Minimum-to-enter. Fires while standing in the area with less than it asks for, which is the
        // only place the client can see the requirement at all - there is no readable signal at the
        // door itself, so this reads as "you are here and short" rather than as a gate warning.
        if (area != null && area.minimumSeconds > 0 && remaining < area.minimumSeconds
                && !zone.equalsIgnoreCase(minimumWarnedZone)) {
            minimumWarnedZone = zone;
            send(cfg, "Not enough Rift Time",
                    area.zone + " needs " + RiftTime.format(area.minimumSeconds) + ", you have "
                            + RiftTime.format(remaining), SbsAudio.Tone.CHIME);
        } else if (area == null || area.minimumSeconds <= 0) {
            minimumWarnedZone = "";
        }

        // Cannot afford to leave. Only for areas the file gives a walk-out estimate for, and never
        // for one where the clock is frozen: standing still in the Mirrorverse costs nothing, so
        // "you are running out" would be false there in the most alarming possible way.
        boolean stranded = area != null && !area.frozen() && area.exitSeconds > 0
                && remaining < area.exitSeconds + EXIT_MARGIN_SECONDS;
        if (stranded && !exitFired) {
            exitFired = true;
            send(cfg, "Leave now",
                    "About " + RiftTime.format(area.exitSeconds) + " to walk out of " + area.zone
                            + ", " + RiftTime.format(remaining) + " left", SbsAudio.Tone.ALARM);
        } else if (!stranded) {
            exitFired = false;
        }
    }

    private void send(SBSConfig.RiftTimeSettings cfg, String title, String detail,
                      SbsAudio.Tone tone) {
        Alerts.send(new Alerts.Alert(title, detail, tone, null), cfg.alertChannels);
    }

    private void reset() {
        warnFired = false;
        criticalFired = false;
        exitFired = false;
        minimumWarnedZone = "";
    }
}
