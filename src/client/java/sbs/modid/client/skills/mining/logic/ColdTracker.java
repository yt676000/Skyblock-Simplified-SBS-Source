/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.logic;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.alert.Alerts;
import sbs.modid.client.core.audio.SbsAudio;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig.MiningHelpersSettings;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.util.PlainText;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The Glacite Cold stat: where it is read, the capture log, and the warning.
 *
 * <p>Gated on the zone (Glacite Tunnels / Glacite Mineshaft) from {@link SkyBlockLocation}. Inside it,
 * the sidebar and the action bar are both searched, because which one carries Cold is UNVERIFIED.
 * Every candidate line is logged once per distinct text as {@code [SBS][Cold]}, so ordinary play
 * produces the real string. A reading older than {@link #FRESH_MS} is unknown, never a guess.
 */
public final class ColdTracker {

    private static final ColdTracker INSTANCE = new ColdTracker();

    private static final long SCAN_MS = 500L;
    /** A reading not refreshed for this long is unknown again. */
    private static final long FRESH_MS = 3_000L;
    /** Distinct capture lines kept per session; a counter line changes with every value. */
    private static final int MAX_LOGGED = 300;

    private final ColdAlarm alarm = new ColdAlarm();
    private final Set<String> logged = new HashSet<>();
    private long lastScanAt;
    private volatile int cold = -1;
    private volatile long readAt;
    private volatile String source = "";

    private ColdTracker() {
    }

    public static ColdTracker getInstance() {
        return INSTANCE;
    }

    private static MiningHelpersSettings cfg() {
        return ConfigManager.getInstance().get().miningHelpers;
    }

    /** Whether the zone is one where Cold builds up. */
    public static boolean inColdZone() {
        String zone = SkyBlockLocation.zone();
        return zone != null && zone.toLowerCase(Locale.ROOT).contains("glacite");
    }

    /** The current Cold, or {@code -1} when unknown (no reading, stale, or not in a Glacite zone). */
    public int cold() {
        return System.currentTimeMillis() - readAt <= FRESH_MS && inColdZone() ? cold : -1;
    }

    /** Called every client tick. */
    public void onClientTick() {
        long now = System.currentTimeMillis();
        if (now - lastScanAt < SCAN_MS) {
            return;
        }
        lastScanAt = now;
        if (!inColdZone()) {
            return;
        }
        for (String line : SkyBlockLocation.sidebarLines()) {
            offer("sidebar", line, now);
        }
        checkAlarm();
    }

    /** Fed the raw action bar from {@code HudMixin.setOverlayMessage}. */
    public void onActionBar(String raw) {
        if (raw != null && inColdZone()) {
            offer("actionbar", raw, System.currentTimeMillis());
        }
    }

    public void onWorldChange() {
        cold = -1;
        readAt = 0;
    }

    private void offer(String where, String raw, long now) {
        String line = PlainText.strip(raw).trim();
        if (!ColdReading.candidate(line)) {
            return;
        }
        int value = ColdReading.parse(line);
        if (logged.size() < MAX_LOGGED && logged.add(where + "|" + raw)) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Cold] {} zone='{}' parsed={} raw='{}'", where,
                    SkyBlockLocation.zone(), value, escape(raw));
        }
        if (value >= 0) {
            cold = value;
            readAt = now;
            source = where;
        }
    }

    private void checkAlarm() {
        MiningHelpersSettings settings = cfg();
        int value = cold();
        int threshold = ColdAlarm.threshold(settings.coldCap, settings.coldWarnPercent);
        if (alarm.update(value, threshold) && settings.enabled && settings.coldWarning) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Cold] warning at {} (threshold {}, cap {}, from {})",
                    value, threshold, settings.coldCap, source);
            Alerts.send(new Alerts.Alert("Cold " + value, "Warm up - Cold is " + value + " of "
                    + settings.coldCap, SbsAudio.Tone.CHIME, null), settings.coldWarningChannels);
        }
    }

    /** Private-use glyphs as {@code \\uXXXX}, the way the HUD-source docs write them. */
    private static String escape(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (char c : raw.toCharArray()) {
            if (c >= 0xE000 && c <= 0xF8FF) {
                out.append(String.format(Locale.ROOT, "\\u%04X", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
