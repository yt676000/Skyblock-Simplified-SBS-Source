/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.prices;

import sbs.modid.client.core.config.ConfigManager;

/**
 * How a value that came from {@link BackupPrices} is marked: a leading {@value #PREFIX}, grey text
 * and the age of the last sighting, so a last known price never reads as a live one.
 */
public final class BackupPriceMarker {

    /** Put in front of any number that contains a backup price. */
    public static final String PREFIX = "≈";

    private BackupPriceMarker() {
    }

    /** Whether "Mark backup prices" is on (and backups are on at all). */
    public static boolean enabled() {
        var cfg = ConfigManager.getInstance().get().itemOverlay;
        return cfg.backupPrices && cfg.markBackupPrices;
    }

    /** "12d ago", "5h ago", "3m ago", "just now". */
    public static String age(long seenAtMs, long nowMs) {
        long minutes = Math.max(0, nowMs - seenAtMs) / 60_000L;
        if (minutes < 1) {
            return "just now";
        }
        if (minutes < 60) {
            return minutes + "m ago";
        }
        long hours = minutes / 60;
        return hours < 48 ? hours + "h ago" : hours / 24 + "d ago";
    }
}
