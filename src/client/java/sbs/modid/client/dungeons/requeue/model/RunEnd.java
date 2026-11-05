/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.requeue.model;

/**
 * A finished run, as {@code RunEndDetector} saw it.
 *
 * @param kind   completed or lost
 * @param target the instance that was played; {@code null} when it could not be told
 * @param line   the chat line that ended it, for the log
 */
public record RunEnd(Kind kind, RequeueTarget.Category category, RequeueTarget target, String line) {

    public enum Kind {
        COMPLETED,
        LOST
    }
}
