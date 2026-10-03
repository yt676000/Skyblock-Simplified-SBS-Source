/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.teamhealth.logic;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Decides when a teammate has gone low, per teammate: threshold crossing plus a cooldown.
 *
 * <p><b>The percentage is against the highest health seen this run.</b> The sidebar prints current
 * health and no maximum, and everyone starts a run at full, so the first readings are the maximum in
 * practice. A teammate first seen already hurt only makes the warning later, never falsely: the
 * reference is too low, so they read as healthier than they are. {@code ESTIMATED} - a mid-run gear
 * swap that raises someone's maximum is followed upward, one that lowers it is not.
 *
 * <p>A warning fires on the crossing - above the threshold on the last reading, below it on this one
 * - and at most once per {@code cooldownMs} per teammate. A dead teammate never warns and is no
 * longer "low"; one that recovers above the threshold is re-armed.
 *
 * <p>Pure: a clock is passed in.
 */
public final class LowHealthMonitor {

    /** A teammate crossing below the threshold. */
    public record Warning(String name, char dungeonClass, int percent) {
    }

    private static final class Member {
        long maxSeen;
        boolean low;
        long lastWarnAt = Long.MIN_VALUE / 2;
    }

    private final Map<String, Member> members = new HashMap<>();

    /**
     * One reading.
     *
     * @return the warning to deliver, or {@code null}
     */
    public Warning update(TeamHealthParser.Reading reading, int thresholdPercent, long cooldownMs,
                          long now) {
        if (reading == null) {
            return null;
        }
        Member member = members.computeIfAbsent(key(reading.name()), k -> new Member());
        if (reading.state() == TeamHealthParser.State.DEAD) {
            member.low = false;
            return null;
        }
        if (reading.state() != TeamHealthParser.State.ALIVE || reading.health() <= 0) {
            return null;
        }
        member.maxSeen = Math.max(member.maxSeen, reading.health());
        int percent = (int) (reading.health() * 100 / member.maxSeen);
        boolean nowLow = percent < thresholdPercent;
        boolean crossed = nowLow && !member.low;
        member.low = nowLow;
        if (!crossed || now - member.lastWarnAt < cooldownMs) {
            return null;
        }
        member.lastWarnAt = now;
        return new Warning(reading.name(), reading.dungeonClass(), percent);
    }

    /** Whether this teammate is below the threshold on their latest reading. */
    public boolean isLow(String name) {
        Member member = name == null ? null : members.get(key(name));
        return member != null && member.low;
    }

    /** The run is over: maxima and cooldowns belong to it. */
    public void reset() {
        members.clear();
    }

    public boolean isEmpty() {
        return members.isEmpty();
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
