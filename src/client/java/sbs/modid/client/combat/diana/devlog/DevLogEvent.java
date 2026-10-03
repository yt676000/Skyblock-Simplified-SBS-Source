/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.diana.devlog;

import com.google.gson.JsonObject;

/**
 * One line of a Diana capture, before the writer numbers it.
 *
 * <p>Built on the client thread from values already read out of a packet or the world - strings and
 * numbers only, never a packet, a {@code Component} or an entity - and handed to the writer thread
 * through its queue. The {@code data} object is filled in completely before the event is queued and
 * never touched again; the queue hand-off is what publishes it to the writer thread.
 *
 * @param t    wall clock when the event was built, epoch milliseconds
 * @param tick client ticks since the capture started
 * @param type the event type, one of the {@link DevLogEvents} constants
 * @param pos  where the event happened, or {@code null} when it has no position
 * @param data the type's payload
 */
public record DevLogEvent(long t, long tick, String type, Pos pos, JsonObject data) {

    /** A position in the world. Block events use the block's corner. */
    public record Pos(double x, double y, double z) {
    }
}
