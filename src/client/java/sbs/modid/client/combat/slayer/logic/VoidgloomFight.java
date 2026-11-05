/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.slayer.logic;

import sbs.modid.SkyblockSimplifiedSBS;

import java.util.Locale;

/**
 * Your own Voidgloom Seraph's shield count and laser phase, fed once per slayer scan (250 ms) with
 * what the scan saw of the boss. No Minecraft types in here, so the whole state machine is tested
 * on plain numbers.
 *
 * <p><b>Shield.</b> The count comes straight off the nametag ({@link VoidgloomShield}); the largest
 * count seen since the shield went up is the denominator for the colour, so no per-tier table of
 * shield sizes is needed.
 *
 * <p><b>Laser phase – ESTIMATED.</b> In the laser phase the boss rises off the ground and hangs in
 * the air, turning in place while it fires. Taken as the signal: the boss at least {@link
 * #LASER_RISE} blocks above the lowest point it stood on this fight, barely moving sideways, for
 * {@link #LASER_SETTLE_MS}. It ends when it comes back down. Neither the signal nor the duration
 * is confirmed against a capture yet: every start and end is logged as {@code [SBS][Voidgloom]}
 * with the measured length, and once one phase has been watched to its end its measured length
 * replaces the estimate for that tier for the rest of the session.
 */
public final class VoidgloomFight {

    /** Blocks above the fight's lowest boss position that count as "lifted". */
    static final double LASER_RISE = 2.0;
    /** Sideways movement per scan below which the boss counts as hanging still. */
    static final double STILL_PER_SCAN = 0.35;
    /** How long it has to hang there before the phase is called – a jump is not a phase. */
    static final long LASER_SETTLE_MS = 500;
    /** Unconfirmed laser length (ms) by tier 1..4 – replaced by the first measured one. */
    private static final long[] ESTIMATED_LASER_MS = {8_000, 8_000, 8_000, 8_000};
    /** A measured phase outside this window is a misread, not a phase length worth keeping. */
    private static final long MIN_MEASURED_MS = 2_000;
    private static final long MAX_MEASURED_MS = 30_000;

    private static final VoidgloomFight INSTANCE = new VoidgloomFight();

    private final long[] measuredLaserMs = new long[4];

    private boolean active;
    private int tier;
    private int hits = -1;
    private int shieldMax;
    private double groundY = Double.NaN;
    private double lastX;
    private double lastZ;
    private boolean hasLast;
    private long liftedSince = -1;
    private long laserStart = -1;
    private long fightStart;

    public static VoidgloomFight getInstance() {
        return INSTANCE;
    }

    /** The boss is gone (killed, failed, world change): forget the fight, keep the measured lengths. */
    public void reset() {
        if (active) {
            log(System.currentTimeMillis(), "fight state cleared");
        }
        active = false;
        hits = -1;
        shieldMax = 0;
        groundY = Double.NaN;
        hasLast = false;
        liftedSince = -1;
        laserStart = -1;
    }

    /**
     * One scan's view of your own boss.
     *
     * @param tier  slayer tier 1..4 from the sidebar, 0 when unknown
     * @param tag   the boss nametag, colour-stripped
     */
    public void update(long now, int tier, String tag, double x, double y, double z) {
        if (!active) {
            active = true;
            fightStart = now;
            log(now, "own boss seen, tier " + tier + ", tag '" + tag + "'");
        }
        this.tier = tier;
        updateShield(now, VoidgloomShield.parseHits(tag), tag);
        updateLaser(now, x, y, z);
    }

    private void updateShield(long now, int parsed, String tag) {
        if (parsed != hits) {
            if (parsed >= 0 && hits < 0) {
                shieldMax = parsed;
                log(now, "shield up: " + parsed + " hits");
            } else if (parsed < 0) {
                log(now, "shield down, tag '" + tag + "'");
                shieldMax = 0;
            }
            if (parsed > shieldMax) {
                shieldMax = parsed;
            }
            hits = parsed;
        }
    }

    private void updateLaser(long now, double x, double y, double z) {
        if (Double.isNaN(groundY) || y < groundY) {
            groundY = y;
        }
        double moved = hasLast ? Math.hypot(x - lastX, z - lastZ) : 0;
        lastX = x;
        lastZ = z;
        hasLast = true;

        boolean lifted = y - groundY >= LASER_RISE;
        if (laserStart < 0) {
            if (lifted && moved <= STILL_PER_SCAN) {
                if (liftedSince < 0) {
                    liftedSince = now;
                } else if (now - liftedSince >= LASER_SETTLE_MS) {
                    laserStart = liftedSince;
                    log(now, String.format(Locale.US, "laser START (est.) - %.1f above ground, %.2f moved",
                            y - groundY, moved));
                }
            } else {
                liftedSince = -1;
            }
        } else if (!lifted) {
            long length = now - laserStart;
            log(now, "laser END (est.) - lasted " + length + " ms");
            if (tier >= 1 && tier <= 4 && length >= MIN_MEASURED_MS && length <= MAX_MEASURED_MS) {
                measuredLaserMs[tier - 1] = length;
            }
            laserStart = -1;
            liftedSince = -1;
        }
    }

    /** Whether a fight of your own is being followed. */
    public boolean active() {
        return active;
    }

    /** Hits the shield still takes, or {@code -1} while it is down. */
    public int shieldHits() {
        return hits;
    }

    /** Largest count seen since the shield went up – the "full" mark for the colour. */
    public int shieldMax() {
        return shieldMax;
    }

    /** Whether the laser phase is running. */
    public boolean laserActive() {
        return laserStart >= 0;
    }

    /** Milliseconds of laser phase left (never below 0); 0 when none is running. */
    public long laserRemainingMs(long now) {
        if (laserStart < 0) {
            return 0;
        }
        return Math.max(0, laserLengthMs() - (now - laserStart));
    }

    /** The length the countdown runs from: the measured one for this tier, else the estimate. */
    public long laserLengthMs() {
        int index = Math.max(1, Math.min(4, tier)) - 1;
        return measuredLaserMs[index] > 0 ? measuredLaserMs[index] : ESTIMATED_LASER_MS[index];
    }

    /** Whether {@link #laserLengthMs} is still the unconfirmed estimate. */
    public boolean laserLengthEstimated() {
        int index = Math.max(1, Math.min(4, tier)) - 1;
        return measuredLaserMs[index] == 0;
    }

    /** Tier out of a sidebar boss line ("Voidgloom Seraph IV"); 0 when none is written. */
    public static int tierOf(String line) {
        if (line == null) {
            return 0;
        }
        String trimmed = line.strip();
        int space = trimmed.lastIndexOf(' ');
        return switch (space < 0 ? trimmed : trimmed.substring(space + 1)) {
            case "I" -> 1;
            case "II" -> 2;
            case "III" -> 3;
            case "IV" -> 4;
            case "V" -> 5;
            default -> 0;
        };
    }

    /** Probe: a title, subtitle or chat line during your own fight, with its time. */
    public void probe(long now, String kind, String text) {
        if (active && text != null && !text.isBlank()) {
            log(now, kind + " '" + text + "'");
        }
    }

    private void log(long now, String what) {
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Voidgloom] t={} {}",
                String.format(Locale.US, "%.2f", (now - fightStart) / 1000.0), what);
    }

    /** Test seam: a fresh instance, independent of the singleton. */
    static VoidgloomFight newForTest() {
        return new VoidgloomFight();
    }

    private VoidgloomFight() {
    }
}
