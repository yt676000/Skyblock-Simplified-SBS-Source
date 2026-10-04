/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.build.logic;

import net.minecraft.network.protocol.Packet;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.dev.DevMode;

import java.util.Map;
import java.util.TreeMap;

/**
 * Developer check for freecam's network rule: counts every packet the client sends, by type, and
 * writes one line every ten seconds labelled with whether freecam was on - so "standing still" and
 * "standing still with freecam" can be compared line for line in the log.
 *
 * <p>Observation only: it reads the packet's class name on the way out and changes nothing. Inert
 * (one boolean check) unless developer mode is on.
 */
public final class FreecamPacketLog {

    private static final long WINDOW_MS = 10_000L;
    private static final Map<String, Integer> COUNTS = new TreeMap<>();
    private static long windowStart;
    private static boolean sawFreecam;
    private static boolean sawNormal;

    private FreecamPacketLog() {
    }

    /** Called for every outgoing packet; returns at once outside developer mode. */
    public static void record(Packet<?> packet) {
        // DEV-ONLY: packet log only
        if (!DevMode.ACTIVE) {
            return;
        }
        synchronized (COUNTS) {
            long now = System.currentTimeMillis();
            if (windowStart == 0) {
                windowStart = now;
            }
            if (now - windowStart >= WINDOW_MS) {
                String mode = sawFreecam && sawNormal ? "mixed" : sawFreecam ? "freecam ON" : "freecam off";
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Freecam] sent in the last 10s ({}): {}", mode, COUNTS);
                COUNTS.clear();
                windowStart = now;
                sawFreecam = false;
                sawNormal = false;
            }
            COUNTS.merge(packet.getClass().getSimpleName(), 1, Integer::sum);
            if (Freecam.active()) {
                sawFreecam = true;
            } else {
                sawNormal = true;
            }
        }
    }
}
