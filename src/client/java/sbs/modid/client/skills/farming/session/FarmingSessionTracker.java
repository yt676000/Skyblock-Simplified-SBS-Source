/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.farming.session;

import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.ProfileContext;
import sbs.modid.client.core.config.ProfileScopedStore;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.core.util.NumberDisplay;
import sbs.modid.client.economy.prices.BazaarPriceCache;
import sbs.modid.client.skills.SkillIslands;
import sbs.modid.client.skills.collection.CollectionCatalog;
import sbs.modid.client.skills.collection.CollectionTracker;
import sbs.modid.client.skills.farming.logic.FarmDropTracker;
import sbs.modid.client.skills.farming.model.CropBlocks;
import sbs.modid.client.skills.farming.model.CropType;
import sbs.modid.client.skills.farming.model.FarmingItems;
import sbs.modid.client.skills.garden.pests.PestProfitTracker;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Farming Session Summary: runs a {@link FarmingSessionClock} off crop breaks, reads the existing
 * trackers' session totals at the start and the end, and keeps the last sessions per profile.
 *
 * <p><b>Counts nothing itself.</b> Crops are the Collection Tracker's exact counter (so they need
 * the tab Collection widget, as the Farming Tracker's profit does), pests are Pest Profit's ledger,
 * rare drops are the farming drop tracker's. A session is {@link FarmingTotals#between} two readings.
 *
 * <p><b>Its own session, not Coins per Hour's.</b> That tracker's session is the purse and the
 * player moving; this one is crops being broken. Walking around the Hub keeps the first alive and
 * must end this one, so sharing would be wrong in both directions.
 */
public final class FarmingSessionTracker implements ProfileScopedStore {

    private static final FarmingSessionTracker INSTANCE = new FarmingSessionTracker();
    private static final String FILE = "farming_sessions.json";

    /** Sessions with less active farming than this are not stored or shown - a stray break. */
    static final long MIN_ACTIVE_MS = 60_000L;

    private FarmingSessionClock clock;
    private long clockPauseMs;
    private long clockEndMs;
    private FarmingTotals startTotals = FarmingTotals.ZERO;
    /** Crop display name -> Bazaar id, for pricing. */
    private final Map<String, String> cropIds = new HashMap<>();

    private List<FarmingSessionRecord> history = new ArrayList<>();
    private boolean loaded;

    private FarmingSessionTracker() {
        ProfileContext.getInstance().register(this);
    }

    public static FarmingSessionTracker getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.GardenHelpersSettings cfg() {
        return ConfigManager.getInstance().get().gardenHelpers;
    }

    /** The clock, rebuilt when its thresholds change between sessions. */
    private FarmingSessionClock clock() {
        long pause = Math.max(5, cfg().farmingSessionPauseSeconds) * 1000L;
        long end = Math.max(1, cfg().farmingSessionEndMinutes) * 60_000L;
        if (clock == null || (!clock.running() && (pause != clockPauseMs || end != clockEndMs))) {
            clock = new FarmingSessionClock(pause, end);
            clockPauseMs = pause;
            clockEndMs = end;
        }
        return clock;
    }

    public boolean running() {
        return clock != null && clock.running();
    }

    // ------------------------------------------------------------------ hooks

    /** A BlockBreakEvents listener: counts the break when it is a crop, farmed with a farming tool. */
    public void onBlockBroken(BlockPos pos, BlockState state) {
        if (!cfg().farmingSession || !SkillIslands.onFarmingIsland()) {
            return;
        }
        var player = Minecraft.getInstance().player;
        if (player == null || !FarmingItems.isFarmingTool(player.getMainHandItem())
                || CropBlocks.of(state, CropType.forHeldTool()) == null) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean started = clock().onCropBroken(now);
        if (started) {
            startTotals = readTotals();
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][FarmSession] started");
        }
    }

    /** Every client tick. */
    public void onClientTick() {
        if (clock == null || !clock.running()) {
            return;
        }
        if (!cfg().farmingSession) {
            clock.endManually();   // switched off mid-session: drop it, show nothing
            return;
        }
        FarmingSessionClock.Ended ended = clock.tick(System.currentTimeMillis(), SkillIslands.onFarmingIsland());
        if (ended != null) {
            finish(ended);
        }
    }

    /** {@code /sbs farming end} and the settings button. Returns whether a session was running. */
    public boolean endNow() {
        FarmingSessionClock.Ended ended = clock == null ? null : clock.endManually();
        if (ended == null) {
            return false;
        }
        finish(ended);
        return true;
    }

    private void finish(FarmingSessionClock.Ended ended) {
        FarmingSessionRecord record = FarmingTotals.between(ended, startTotals, readTotals(), this::cropPrice);
        startTotals = FarmingTotals.ZERO;
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][FarmSession] ended ({}): active {}s, {} crop(s), {} coins",
                ended.reason().text(), record.activeMs / 1000, record.totalCrops(), record.totalCoins());
        if (record.activeMs < MIN_ACTIVE_MS) {
            return;
        }
        ensureLoaded();
        history.add(0, record);
        int keep = Math.max(1, cfg().farmingSessionHistory);
        while (history.size() > keep) {
            history.remove(history.size() - 1);
        }
        save();
        if (cfg().farmingSessionChat) {
            sbs.modid.client.social.chat.logic.SBSChat.send(chatLine(record, previous(0)));
        }
        if (cfg().farmingSessionAutoShow && sbs.modid.client.core.api.GuiStateManager.getInstance().getCurrentScreen() == null) {
            Minecraft.getInstance().setScreenAndShow(new FarmingSessionScreen(null));
        }
    }

    /** Your own numbers only - nothing about other players is in a session. */
    static String chatLine(FarmingSessionRecord r, FarmingSessionRecord prev) {
        String coins = NumberDisplay.format(r.totalCoins());
        String vs = prev == null ? "" : " (" + FarmingSessionRecord.delta(r.totalCoins(),
                (double) prev.totalCoins(), NumberDisplay::format) + " vs last)";
        return "§aFarming session: §f" + FarmingSessionScreen.duration(r.activeMs) + "§7, §f"
                + NumberDisplay.format(r.totalCrops()) + " crops§7, §6" + coins + " coins" + vs
                + "§7 - /sbs farming for the summary";
    }

    // ------------------------------------------------------------------ readings

    private FarmingTotals readTotals() {
        Map<String, Long> crops = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : CollectionTracker.getInstance().sessionGains().entrySet()) {
            CropType crop = cropFor(e.getKey());
            if (crop != null) {
                crops.merge(crop.displayName(), e.getValue(), Long::sum);
                cropIds.put(crop.displayName(), crop.bazaarId());
            }
        }
        var pests = PestProfitTracker.getInstance().session();
        boolean traps = cfg().pestProfitTrapLoot;
        FarmDropTracker drops = FarmDropTracker.getInstance();
        boolean[] unpriced = new boolean[1];
        long dropCoins = drops.sessionValue(unpriced);
        return new FarmingTotals(crops, pests.totalKills(),
                Math.round(pests.value(PestProfitTracker::price, traps)),
                drops.session(), dropCoins, unpriced[0]);
    }

    private static CropType cropFor(String collectionId) {
        String key = CollectionCatalog.normalizeKey(collectionId);
        for (CropType crop : CropType.values()) {
            if (CollectionCatalog.normalizeKey(crop.bazaarId()).equals(key)) {
                return crop;
            }
        }
        return null;
    }

    /** Instasell price, the Farming Tracker's rule; -1 when the Bazaar has none. */
    private long cropPrice(String cropName) {
        String id = cropIds.get(cropName);
        BazaarPriceCache.BzPrice price = id == null ? null : BazaarPriceCache.getInstance().get(id);
        return price == null || price.sell() <= 0 ? -1 : price.sell();
    }

    // ------------------------------------------------------------------ history

    /** Newest first. */
    public synchronized List<FarmingSessionRecord> history() {
        ensureLoaded();
        return List.copyOf(history);
    }

    /** The session before {@code index} in {@link #history()}, or null. */
    public synchronized FarmingSessionRecord previous(int index) {
        ensureLoaded();
        return index + 1 < history.size() ? history.get(index + 1) : null;
    }

    private void ensureLoaded() {
        if (!loaded) {
            reloadProfile();
        }
    }

    @Override
    public synchronized void reloadProfile() {
        // A running session's start readings belong to the profile being left.
        if (clock != null) {
            clock.endManually();
        }
        startTotals = FarmingTotals.ZERO;
        List<FarmingSessionRecord> read = new ArrayList<>();
        try {
            Path path = ProfileContext.getInstance().file(FILE);
            if (Files.isRegularFile(path)) {
                List<FarmingSessionRecord> parsed = SBSFiles.GSON.fromJson(
                        Files.readString(path, StandardCharsets.UTF_8),
                        new TypeToken<ArrayList<FarmingSessionRecord>>() { }.getType());
                if (parsed != null) {
                    parsed.removeIf(r -> r == null);
                    read.addAll(parsed);
                }
            }
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][FarmSession] could not read {} ({})", FILE, e.toString());
        }
        history = read;
        loaded = true;
    }

    @Override
    public synchronized void flushProfile() {
        if (loaded) {
            save();
        }
    }

    private synchronized void save() {
        if (!ProfileContext.getInstance().known()) {
            return;
        }
        try {
            Path path = ProfileContext.getInstance().file(FILE);
            Files.createDirectories(path.getParent());
            Files.writeString(path, SBSFiles.GSON.toJson(history), StandardCharsets.UTF_8);
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][FarmSession] could not write {}", FILE, e);
        }
    }
}
