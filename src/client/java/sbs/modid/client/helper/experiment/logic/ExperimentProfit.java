/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.experiment.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.ProfileContext;
import sbs.modid.client.core.config.ProfileScopedStore;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.economy.itemvalue.ItemAppraisal;
import sbs.modid.client.helper.enchants.EnchantNames;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * Experimentation Table profit: books every claim and its rewards into this session's ledger and
 * today's (per profile, file {@value #FILE}), fed by {@link ExperimentRewardParser}.
 *
 * <h2>Why not TrackerStore</h2>
 *
 * <p>{@code TrackerStore} is a write-only lifetime log shared by every profile; the card reads its
 * counts back per profile, so today's ledger is a profile file like the Dice Profit one.
 *
 * <h2>The probe (on while the tracker is enabled)</h2>
 *
 * <p>The reward list is captured only for bottles so far. For {@value #WINDOW_MS} ms after a claim
 * line every chat line is logged as {@code [SBS][ExperimentProfit] after claim: ...}, and when the
 * window closes the player inventory is compared with a snapshot taken before the claim and every
 * gained item is logged as {@code [SBS][ExperimentProfit] inventory +N name}. That diff is the
 * reference if the chat list turns out to be incomplete. Bazaar experience-bottle purchases are
 * logged too, not charged: no capture shows the table consuming bottles.
 */
public final class ExperimentProfit implements ProfileScopedStore {

    private static final ExperimentProfit INSTANCE = new ExperimentProfit();

    private static final String FILE = "experiment_profit.json";
    /** How long after a claim line the reward lines are read and every line is logged. */
    static final long WINDOW_MS = 3_000L;
    /** How often the "before" inventory snapshot is refreshed while no window is open. */
    private static final long SNAPSHOT_MS = 1_000L;

    /** What the file holds: today's ledger and the day it belongs to. */
    private static final class Saved {
        String day;
        ExperimentLedger ledger;
    }

    private volatile ExperimentLedger session = new ExperimentLedger();
    private volatile ExperimentLedger today = new ExperimentLedger();
    private volatile String day = LocalDate.now().toString();
    private volatile boolean loaded;
    private volatile int version;

    private long windowUntil;
    private long lastSnapshotAt;
    /** The newest snapshot and the one before it; a claim compares against the older one. */
    private Map<String, Integer> snapshot = Map.of();
    private Map<String, Integer> previousSnapshot = Map.of();
    private Map<String, Integer> claimBase;

    private ExperimentProfit() {
        ProfileContext.getInstance().register(this);
    }

    public static ExperimentProfit getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.ExperimentationSettings cfg() {
        return ConfigManager.getInstance().get().experimentation;
    }

    /** Bumped on every change the card shows. */
    public int version() {
        return version;
    }

    public ExperimentLedger session() {
        return session;
    }

    public ExperimentLedger today() {
        ensureLoaded();
        rollDay();
        return today;
    }

    /** Prices rewards on the configured side of the market: 0 = sell, 1 = buy. */
    public static ExperimentLedger.Pricer pricer(int side) {
        ItemAppraisal.Side s = side == 1 ? ItemAppraisal.Side.BUY : ItemAppraisal.Side.SELL;
        return new ExperimentLedger.Pricer() {
            @Override
            public Long item(String itemId) {
                return ItemAppraisal.price(itemId, s);
            }

            @Override
            public Long book(String enchant, int tier) {
                return ItemAppraisal.bookPrice(EnchantNames.resolveBase(enchant), tier, s);
            }
        };
    }

    /** A chat line that passed the prefilter. */
    public void onChat(String raw) {
        if (!cfg().profitTracker) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now >= windowUntil && raw != null && !raw.contains("claimed the") && !raw.contains("Experience Bottle")) {
            return;
        }
        String clean = ExperimentRewardParser.clean(raw);
        ExperimentRewardParser.Game game = ExperimentRewardParser.claim(clean);
        if (game != null) {
            ensureLoaded();
            rollDay();
            session.claim(game, now);
            today.claim(game, now);
            windowUntil = now + WINDOW_MS;
            claimBase = previousSnapshot.isEmpty() ? snapshot : previousSnapshot;
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][ExperimentProfit] claim {}", game);
            version++;
            save();
            return;
        }
        if (now < windowUntil) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][ExperimentProfit] after claim: {}", clean);
            ExperimentRewardParser.Reward reward = ExperimentRewardParser.reward(clean);
            if (reward != null) {
                session.reward(reward);
                today.reward(reward);
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][ExperimentProfit] reward {} x{} {}", reward.kind(),
                        reward.amount(), reward.kind() == ExperimentRewardParser.Kind.BOOK
                                ? reward.enchant() + " " + reward.tier() : reward.itemId());
                version++;
                save();
            }
            return;
        }
        ExperimentRewardParser.BottlePurchase bought = ExperimentRewardParser.bottlePurchase(clean);
        if (bought != null) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][ExperimentProfit] bazaar bought {}x {} for {}",
                    bought.count(), bought.itemId(), bought.coins());
        }
    }

    /** Every client tick: the inventory snapshots for the probe, once a second, and the window close. */
    public void onClientTick(Minecraft minecraft) {
        if (!cfg().profitTracker || minecraft.player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (claimBase != null) {
            if (now >= windowUntil) {
                logInventoryDiff(claimBase, inventory(minecraft.player.getInventory()));
                claimBase = null;
                lastSnapshotAt = 0L;
            }
            return;
        }
        if (now - lastSnapshotAt < SNAPSHOT_MS) {
            return;
        }
        lastSnapshotAt = now;
        previousSnapshot = snapshot;
        snapshot = inventory(minecraft.player.getInventory());
    }

    /** Display name to total count over the main inventory and hotbar. */
    private static Map<String, Integer> inventory(Inventory inventory) {
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                counts.merge(stack.getHoverName().getString(), stack.getCount(), Integer::sum);
            }
        }
        return counts;
    }

    private static void logInventoryDiff(Map<String, Integer> before, Map<String, Integer> after) {
        int logged = 0;
        for (Map.Entry<String, Integer> e : after.entrySet()) {
            int gained = e.getValue() - before.getOrDefault(e.getKey(), 0);
            if (gained > 0) {
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][ExperimentProfit] inventory +{} {}", gained, e.getKey());
                logged++;
            }
        }
        if (logged == 0) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][ExperimentProfit] inventory: nothing new after the claim");
        }
    }

    /** Starts this session's ledger again from zero. */
    public void resetSession() {
        session = new ExperimentLedger();
        version++;
    }

    /** Clears today's ledger too. */
    public void resetToday() {
        ensureLoaded();
        today = new ExperimentLedger();
        session = new ExperimentLedger();
        version++;
        save();
    }

    private void rollDay() {
        String now = LocalDate.now().toString();
        if (!now.equals(day)) {
            day = now;
            today = new ExperimentLedger();
            version++;
        }
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
        ExperimentLedger read = new ExperimentLedger();
        String readDay = LocalDate.now().toString();
        try {
            Path path = file();
            if (Files.isRegularFile(path)) {
                try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    Saved parsed = SBSFiles.GSON.fromJson(reader, Saved.class);
                    if (parsed != null && parsed.ledger != null && readDay.equals(parsed.day)) {
                        read = parsed.ledger;
                    }
                }
            }
        } catch (Exception e) {
            // A corrupt file is logged, not fatal; it stays on disk and memory starts empty.
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][ExperimentProfit] could not read {} ({})", FILE, e.toString());
        }
        today = read;
        day = readDay;
        session = new ExperimentLedger();
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
            Saved saved = new Saved();
            saved.day = day;
            saved.ledger = today;
            Path path = file();
            Files.createDirectories(path.getParent());
            Files.writeString(path, SBSFiles.GSON.toJson(saved), StandardCharsets.UTF_8);
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][ExperimentProfit] could not write {} ({})", FILE, e.toString());
        }
    }
}
