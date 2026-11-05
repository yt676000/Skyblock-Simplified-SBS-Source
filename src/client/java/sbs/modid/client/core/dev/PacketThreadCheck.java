/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.dev;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import sbs.modid.SkyblockSimplifiedSBS;

/**
 * Dev-only tripwire for packet-handler mixins that are meant to run on the client thread only.
 *
 * <p>{@code ClientPacketListener} handlers are entered twice - first on the netty thread, where
 * {@code ensureRunningOnSameThread} reschedules the packet and throws, then on the client thread. A
 * hook placed at HEAD (or anywhere before that call) fires on both. Every SBS hook that is supposed
 * to sit after the redirect calls {@link #check} first, so one that slips back in front of it is
 * reported in the log, once per hook.
 *
 * <p>On in a development environment, or with {@code -Dsbs.debug.packetThreads=true}. Off, a check
 * is one read of a {@code static final} the JIT folds away; on, it is one {@code isSameThread()}.
 * Hooks that run on the netty thread by design ({@code Connection} hooks, the resource-pack push
 * decision) do not call it.
 */
public final class PacketThreadCheck {

    public static final boolean ENABLED = FabricLoader.getInstance().isDevelopmentEnvironment()
            || Boolean.getBoolean("sbs.debug.packetThreads");

    /** Hooks already reported; written only on the failure path. */
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private PacketThreadCheck() {
    }

    /** Logs once if {@code hook} is running off the client thread. {@code hook} is a constant. */
    public static void check(String hook) {
        if (ENABLED && !Minecraft.getInstance().isSameThread() && REPORTED.add(hook)) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Dev] packet hook {} ran on thread '{}', not the client thread",
                    hook, Thread.currentThread().getName(), new Throwable("stack"));
        }
    }
}
