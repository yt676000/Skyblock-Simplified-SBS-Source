/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.diana.devlog;

import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * How many packets of each class arrived, per second - the part of the capture that finds the packet
 * types nobody thought to log.
 *
 * <p>Incremented on the <b>netty thread</b>, from the inbound read, before the packet is handed to
 * the client thread; read and reset on the client thread once a second. That is the whole reason for
 * {@link LongAdder} and a concurrent map: the increment is the only work done on the network thread,
 * and it must never block or log there. A class seen for the first time allocates one map entry; after
 * that an increment allocates nothing.
 *
 * <p>A bundle is counted once under its own class in {@code counts}, and every packet inside it once
 * under its own class in {@code bundled} - so a reader can tell a packet that arrives alone from one
 * that only ever arrives grouped, and neither count is double.
 */
public final class DevLogPackets {

    private static final Map<Class<?>, LongAdder> COUNTS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, LongAdder> BUNDLED = new ConcurrentHashMap<>();

    private DevLogPackets() {
    }

    /** One inbound packet. Netty thread; the caller has already checked the log is on. */
    public static void count(Packet<?> packet) {
        if (packet == null) {
            return;
        }
        COUNTS.computeIfAbsent(packet.getClass(), k -> new LongAdder()).increment();
        if (packet instanceof BundlePacket<?> bundle) {
            for (Packet<?> inner : bundle.subPackets()) {
                if (inner != null) {
                    BUNDLED.computeIfAbsent(inner.getClass(), k -> new LongAdder()).increment();
                }
            }
        }
    }

    /** Counts since the last call, by simple class name, sorted; empty classes left out. */
    public static Map<String, Long> drainCounts() {
        return drain(COUNTS);
    }

    /** Bundled counts since the last call, by simple class name, sorted. */
    public static Map<String, Long> drainBundled() {
        return drain(BUNDLED);
    }

    /** Forgets everything - a new capture starts from zero. */
    public static void reset() {
        COUNTS.clear();
        BUNDLED.clear();
    }

    private static Map<String, Long> drain(Map<Class<?>, LongAdder> from) {
        Map<String, Long> out = new TreeMap<>();
        from.forEach((type, adder) -> {
            long n = adder.sumThenReset();
            if (n > 0) {
                out.merge(type.getSimpleName(), n, Long::sum);
            }
        });
        return out;
    }
}
