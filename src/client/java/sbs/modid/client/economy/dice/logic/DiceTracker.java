/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.dice.logic;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.ProfileContext;
import sbs.modid.client.core.config.ProfileScopedStore;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.economy.coinsperhour.logic.CoinLines;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Dice Profit: the session's and the profile's all-time rolls, fed by {@link DiceParser}.
 *
 * <h2>Why not TrackerStore</h2>
 *
 * <p>{@code TrackerStore} is a write-only lifetime log shared by every profile; the card needs
 * all-time counts it can read back, per profile. So the all-time ledger is a profile file
 * ({@value #FILE}) like the Forge timers, and the session ledger lives in memory and starts over
 * with a profile switch.
 *
 * <h2>The probe</h2>
 *
 * <p>Until the cost, the 6 payout, the 7 and the High Class wording are captured, every dice-shaped
 * line the parser does not read is logged as {@code [SBS][Dice] unread: ...}, and each roll logs the
 * purse before the line and every purse change in the {@value #PROBE_MS} ms after it. The purse comes
 * off the shared scoreboard snapshot - no sidebar read of its own.
 */
public final class DiceTracker implements ProfileScopedStore {

    private static final DiceTracker INSTANCE = new DiceTracker();

    private static final String FILE = "dice_profit.json";
    /** How long after a roll line purse changes are logged. */
    private static final long PROBE_MS = 5_000L;
    /** How often the "purse before" is sampled while no roll is pending. */
    private static final long SAMPLE_MS = 1_000L;

    private volatile DiceLedger session = new DiceLedger();
    private volatile DiceLedger allTime = new DiceLedger();
    private volatile boolean loaded;
    private volatile int version;
    /** {@code System.currentTimeMillis()} of the last roll of any kind; not persisted. */
    private volatile long lastRollAt;

    private long lastPurse = -1L;
    private long lastSampleAt;
    private long probeUntil;
    private long probePurse = -1L;

    private DiceTracker() {
        ProfileContext.getInstance().register(this);
    }

    public static DiceTracker getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.DiceProfitSettings cfg() {
        return ConfigManager.getInstance().get().diceProfit;
    }

    /** Bumped on every change the card shows. */
    public int version() {
        return version;
    }

    /** When the last roll was read, 0 before the first one. */
    public long lastRollAt() {
        return lastRollAt;
    }

    public DiceLedger session() {
        return session;
    }

    public DiceLedger allTime() {
        ensureLoaded();
        return allTime;
    }

    /** A chat line that passed the "Archfiend" / "Dice rolled" prefilter. */
    public void onChat(String line) {
        if (!cfg().enabled) {
            return;
        }
        DiceParser.Event event = DiceParser.parse(line);
        if (event == null) {
            String clean = DiceParser.clean(line);
            if (DiceParser.candidate(clean)) {
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Dice] unread: {}", clean);
            }
            return;
        }
        if (event.type() == DiceParser.Type.DROP) {
            // A dice dropping is not a roll; logged so the probe shows the line was read.
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Dice] drop: {}", event.kind());
            return;
        }
        long purse = readPurse();
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Dice] roll {} {} bonus {} | purse before {}, at line {}",
                event.kind(), event.value(), event.bonus(), lastPurse, purse);
        long now = System.currentTimeMillis();
        lastRollAt = now;
        probeUntil = now + PROBE_MS;
        probePurse = purse;
        ensureLoaded();
        session.roll(event.kind(), event.value());
        allTime.roll(event.kind(), event.value());
        version++;
        save();
    }

    /** Every client tick: the purse sample for the probe, once a second or every tick after a roll. */
    public void onClientTick() {
        if (!cfg().enabled) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean probing = now < probeUntil;
        if (!probing && now - lastSampleAt < SAMPLE_MS) {
            return;
        }
        lastSampleAt = now;
        long purse = readPurse();
        if (probing && purse >= 0 && purse != probePurse) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Dice] purse {} -> {} ({})", probePurse, purse,
                    probePurse < 0 ? "?" : String.valueOf(purse - probePurse));
            probePurse = purse;
        }
        if (purse >= 0) {
            lastPurse = purse;
        }
    }

    private static long readPurse() {
        for (String line : SkyBlockLocation.sidebarLines()) {
            long purse = CoinLines.purse(line);
            if (purse >= 0) {
                return purse;
            }
        }
        return -1L;
    }

    public void resetSession() {
        session = new DiceLedger();
        version++;
    }

    /** Clears this profile's all-time counts too. */
    public void resetAllTime() {
        ensureLoaded();
        allTime = new DiceLedger();
        session = new DiceLedger();
        version++;
        save();
    }

    private Path file() {
        return ProfileContext.getInstance().file(FILE);
    }

    private void ensureLoaded() {
        if (!loaded) {
            reloadProfile();
        }
    }

    @Override
    public synchronized void reloadProfile() {
        DiceLedger read = new DiceLedger();
        try {
            Path path = file();
            if (Files.isRegularFile(path)) {
                try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    DiceLedger parsed = SBSFiles.GSON.fromJson(reader, DiceLedger.class);
                    if (parsed != null) {
                        read = parsed;
                    }
                }
            }
        } catch (Exception e) {
            // A corrupt file is logged, not fatal; it stays on disk and memory starts empty.
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Dice] could not read {} ({})", FILE, e.toString());
        }
        allTime = read;
        session = new DiceLedger();
        loaded = true;
        version++;
    }

    @Override
    public void flushProfile() {
        if (loaded) {
            save();
        }
    }

    private synchronized void save() {
        if (!loaded || !ProfileContext.getInstance().known()) {
            return;
        }
        try {
            Path path = file();
            Files.createDirectories(path.getParent());
            Files.writeString(path, SBSFiles.GSON.toJson(allTime), StandardCharsets.UTF_8);
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Dice] could not write {} ({})", FILE, e.toString());
        }
    }
}
