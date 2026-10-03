/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.coinsperhour.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.ProfileContext;
import sbs.modid.client.core.config.ProfileScopedStore;
import sbs.modid.client.core.config.SBSConfig.CoinsPerHourSettings;
import sbs.modid.client.core.location.SkyBlockLocation;

/**
 * Feeds {@link CoinLedger} from the game: the purse off the sidebar, the coin chat lines, and the
 * player's movement as the activity signal for the idle clock.
 *
 * <p>The session belongs to one profile: a profile switch resets it through the
 * {@link ProfileScopedStore} handshake (nothing is persisted - {@link #flushProfile} has nothing to
 * write), and a world change makes the next purse reading a new baseline.
 */
public final class CoinsPerHourTracker implements ProfileScopedStore {

    private static final CoinsPerHourTracker INSTANCE = new CoinsPerHourTracker();

    /** The sidebar is re-read at this cadence; the purse updates on a server tick at best. */
    private static final long SCAN_MS = 500L;

    private final CoinLedger ledger = new CoinLedger();
    private long lastScanAt;
    private Vec3 lastPos;
    private float lastYaw;
    private float lastPitch;

    private CoinsPerHourTracker() {
        ProfileContext.getInstance().register(this);
    }

    public static CoinsPerHourTracker getInstance() {
        return INSTANCE;
    }

    private static CoinsPerHourSettings cfg() {
        return ConfigManager.getInstance().get().coinsPerHour;
    }

    public CoinLedger ledger() {
        return ledger;
    }

    public long idleMs() {
        return Math.max(1, cfg().idleMinutes) * 60_000L;
    }

    /** Every client tick. The movement check is per tick; the sidebar read is throttled. */
    public void onClientTick() {
        if (!cfg().enabled) {
            return;
        }
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Vec3 pos = player.position();
        if (lastPos == null || pos.distanceToSqr(lastPos) > 1.0E-4
                || player.getYRot() != lastYaw || player.getXRot() != lastPitch) {
            ledger.onActivity(now, idleMs());
            lastPos = pos;
            lastYaw = player.getYRot();
            lastPitch = player.getXRot();
        }
        if (now - lastScanAt < SCAN_MS) {
            return;
        }
        lastScanAt = now;
        for (String line : SkyBlockLocation.sidebarLines()) {
            long purse = CoinLines.purse(line);
            if (purse >= 0) {
                ledger.onPurse(purse, now);
                break;
            }
        }
        ledger.tick(now, idleMs());
    }

    /** Every displayed chat line. */
    public void onChat(String text) {
        if (!cfg().enabled) {
            return;
        }
        ledger.onEvent(CoinLines.classify(text), System.currentTimeMillis());
    }

    public void onWorldChange() {
        ledger.rebaseline();
    }

    /** The reset button and {@code /sbs cph reset}. */
    public void reset() {
        ledger.reset();
    }

    @Override
    public void flushProfile() {
        // The session is not persisted; there is nothing to write.
    }

    @Override
    public void reloadProfile() {
        ledger.reset();
    }
}
