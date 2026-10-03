/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.damage.logic;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Feeds the Melee Damage card. Melee damage never reaches chat – it only exists as the ownerless
 * splash armor stands in the world – so the numbers come from {@link DamageAttribution}, which is
 * the part of SBS that already works out which splash was yours. Only splashes matched to a
 * <b>melee</b> attack window are recorded; bow shots and ability casts open their own windows and
 * are skipped, or the card would not be a melee card.
 *
 * <p>Everything is derived from one rolling window (the configured hold time): the last hit, the
 * best hit in it, and the DPS over it. Stop swinging and the window empties, the card disappears
 * and the next fight starts from scratch – no session state to reset by hand.
 *
 * <p><b>Accuracy.</b> On whitelisted mobs the attribution is the calibrated one Damage Attribution
 * documents. Off the whitelist it is the same click+timer+value match without the solo-calibration
 * guarantee, so in a crowd of players hitting the same mob a foreign splash can land on the card.
 * Nothing is hidden or altered there – a wrong number is the whole of the downside.
 */
public final class MeleeDamageTracker {

    private static final MeleeDamageTracker INSTANCE = new MeleeDamageTracker();

    /** Hard cap on retained hits – a long Ferocity fight must not grow this without bound. */
    private static final int MAX_RETAINED = 400;

    private record Hit(long value, boolean crit, long time) {
    }

    /** Newest first. Guarded by {@code this} – written from the client tick, read while rendering. */
    private final Deque<Hit> hits = new ArrayDeque<>();

    private MeleeDamageTracker() {
    }

    public static MeleeDamageTracker getInstance() {
        return INSTANCE;
    }

    /** What the card draws: everything derived from the current window in one consistent read. */
    public record Snapshot(long last, boolean lastCrit, long best, double dps, int count) {
    }

    private static SBSConfig.DamageAttributionSettings cfg() {
        return ConfigManager.getInstance().get().damageAttribution;
    }

    /** One own melee splash, from {@link DamageAttribution}'s attribution step. */
    public void record(long value, boolean crit) {
        if (!cfg().meleeHud || value <= 0) {
            return;
        }
        synchronized (this) {
            hits.addFirst(new Hit(value, crit, System.currentTimeMillis()));
            while (hits.size() > MAX_RETAINED) {
                hits.removeLast();
            }
        }
    }

    /** The current window, or {@code null} when nothing was hit recently (card stays away). */
    public Snapshot snapshot() {
        long now = System.currentTimeMillis();
        long hold = Math.max(1, cfg().meleeHoldSeconds) * 1000L;
        synchronized (this) {
            hits.removeIf(hit -> now - hit.time() > hold);
            if (hits.isEmpty()) {
                return null;
            }
            Hit newest = hits.peekFirst();
            long best = 0;
            long sum = 0;
            long oldest = now;
            for (Hit hit : hits) {
                best = Math.max(best, hit.value());
                sum += hit.value();
                oldest = Math.min(oldest, hit.time());
            }
            // Measured against "how long ago the window started", not the span between first and
            // last hit, so the rate decays while you are not swinging instead of freezing.
            double seconds = Math.max(1.0, (now - oldest) / 1000.0);
            return new Snapshot(newest.value(), newest.crit(), best, sum / seconds, hits.size());
        }
    }

    /** Drops the window (turning the card off / leaving a fight behind). */
    public synchronized void clear() {
        hits.clear();
    }
}
