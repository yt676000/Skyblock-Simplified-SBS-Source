/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.carry.logic;

import sbs.modid.client.combat.carry.model.SlayerBoss;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * Decides whether a slayer boss that vanished was actually killed. Pure logic, no game types, so the
 * timing rules can be tested on scripted sequences.
 *
 * <p>A boss's body is not one entity for the whole fight. Mid-fight the entity under the nametag
 * changes id (the play-instance logs show {@code tracks=4} with {@code bossStands=1} during Voidgloom
 * and Tarantula fights), and each swap looked like "the boss is gone" to the old one-shot check, so
 * every phase counted as a kill. Now a vanish is only held as <i>pending</i>: when a boss of the same
 * type and owner shows up again near where it vanished within {@link #graceMs}, it was the same fight
 * and the pending death is dropped. Only a vanish that stays vanished for the whole window confirms.
 *
 * <p>Slayer mid-fight transitions, as far as they matter here:
 * <ul>
 *   <li>Voidgloom: the "Hits" shield and the beacon phase. The body changes id during the fight (log
 *       evidence), and the boss cannot die while shielded, so a vanish in the hits phase is refused.</li>
 *   <li>Tarantula: the T5 burrow (the boss leaves and re-emerges) and the rider stack; the logs show
 *       the same track churn.</li>
 *   <li>Inferno: the demons and attunement shields spawn separate entities; the boss stand stays.</li>
 *   <li>Sven (pups, "Protected"), Revenant (enrage) and Riftstalker (twinclaws, ichor): no body swap
 *       known. The grace window covers them anyway.</li>
 * </ul>
 */
final class CarryKillJudge {

    /** A vanished boss that has not yet stayed vanished long enough to count. */
    record Pending(SlayerBoss boss, String owner, double x, double y, double z, long vanishedAt,
                   boolean everClose, long lastCloseMs) {
    }

    /** Why a vanish never became pending - for the log line. */
    enum Refusal { NONE, SHIELDED }

    private long graceMs;
    private final long cooldownMs;
    private final double reappearRadiusSq;
    private final List<Pending> pending = new ArrayList<>();
    private long lastCountAt = Long.MIN_VALUE / 2;

    CarryKillJudge(long graceMs, long cooldownMs, double reappearRadius) {
        this.graceMs = graceMs;
        this.cooldownMs = cooldownMs;
        this.reappearRadiusSq = reappearRadius * reappearRadius;
    }

    /**
     * A tracked boss vanished at {@code now}. {@code shielded} is whether its last tag was a phase
     * marker in which it cannot die (Voidgloom's "Hits"). Returns why it was refused, or
     * {@link Refusal#NONE} when it is now pending. {@code everClose}/{@code lastCloseMs} are handed
     * back if the fight continues, so the new body inherits them.
     */
    Refusal vanished(SlayerBoss boss, String owner, double x, double y, double z, long now,
                     boolean shielded, boolean everClose, long lastCloseMs) {
        if (shielded) {
            return Refusal.SHIELDED;
        }
        pending.add(new Pending(boss, owner, x, y, z, now, everClose, lastCloseMs));
        return Refusal.NONE;
    }

    /**
     * A boss is visible at {@code now}. A pending death of the same type and owner near this spot is
     * the same fight continuing, so it is cancelled and returned (or {@code null} when none matched).
     */
    Pending seen(SlayerBoss boss, String owner, double x, double y, double z, long now) {
        Iterator<Pending> it = pending.iterator();
        while (it.hasNext()) {
            Pending p = it.next();
            // A body that turned up a scan before its owner line was parsed has no owner yet - it
            // may still be the same fight, so it matches on type and place alone.
            boolean ownerMatches = owner == null || Objects.equals(p.owner(), owner);
            if (p.boss() != boss || !ownerMatches || now - p.vanishedAt() > graceMs) {
                continue;
            }
            double dx = p.x() - x, dy = p.y() - y, dz = p.z() - z;
            if (dx * dx + dy * dy + dz * dz <= reappearRadiusSq) {
                it.remove();
                return p;
            }
        }
        return null;
    }

    /** Pending deaths whose grace window has passed without the boss coming back: real kills. */
    List<Pending> confirmed(long now) {
        List<Pending> out = new ArrayList<>();
        Iterator<Pending> it = pending.iterator();
        while (it.hasNext()) {
            Pending p = it.next();
            if (now - p.vanishedAt() > graceMs) {
                it.remove();
                out.add(p);
            }
        }
        return out;
    }

    /** Whether a confirmed kill may be booked now - at most one per cooldown. */
    boolean mayClaim(long now) {
        return now - lastCountAt > cooldownMs;
    }

    /** Starts the cooldown: a kill was booked at {@code now}. */
    void claimed(long now) {
        lastCountAt = now;
    }

    /** The grace window is a setting, so it is re-read every scan. */
    void setGraceMs(long graceMs) {
        this.graceMs = graceMs;
    }

    int pendingCount() {
        return pending.size();
    }

    void clear() {
        pending.clear();
    }
}
