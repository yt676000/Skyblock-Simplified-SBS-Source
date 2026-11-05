/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.kuudraprofit.logic;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.combat.kuudra.logic.KuudraTracker;
import sbs.modid.client.combat.kuudra.model.KuudraPhase;
import sbs.modid.client.combat.kuudraprofit.model.KuudraRun;
import sbs.modid.client.core.async.SbsExecutors;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.ProfileContext;
import sbs.modid.client.core.config.ProfileScopedStore;
import sbs.modid.client.core.config.SBSConfig.KuudraProfitSettings;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.core.config.SaveThrottle;
import sbs.modid.client.core.item.SkyblockItem;
import sbs.modid.client.dungeons.chest.DungeonChestValue;
import sbs.modid.client.dungeons.requeue.model.InstanceIds;
import sbs.modid.client.economy.prices.BazaarPriceCache;
import sbs.modid.client.economy.prices.LbinCache;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemCatalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The Kuudra Profit Tracker's connection to the game: chat lines in, the run's tier and length read
 * from {@link KuudraTracker}, the profile's {@code kuudra_profit.json} written. The bookkeeping is
 * {@link KuudraRunLedger}; the line shapes are {@link KuudraLoot}.
 *
 * <p>Read-only: it reads chat and the Kuudra state, never clicks, opens or sends anything, and stores
 * no other player's name.
 *
 * <p><b>The probe.</b> For {@value #PROBE_MS} ms after a reward block or a run end, every chat line
 * is logged as {@code [SBS][KuudraProfit] probe: ...}, to capture the lines nobody has recorded yet
 * (a per-item claim line, essence lines, a key price).
 */
public final class KuudraProfitTracker implements ProfileScopedStore {

    private static final String FILE = "kuudra_profit.json";
    private static final long BLOCK_IDLE_MS = 750L;
    static final long PROBE_MS = 10_000L;

    private static volatile long probeUntil;
    private static KuudraProfitTracker instance;

    private final KuudraLoot parser = new KuudraLoot();
    private final KuudraRunLedger ledger = new KuudraRunLedger(System.currentTimeMillis());
    private final SaveThrottle throttle = new SaveThrottle();
    private final Valuation valuation = new Valuation();

    private long lastLineAt;
    /** A run is live and its end not yet booked: the next KUUDRA DOWN / DEFEAT is its end. */
    private boolean armed;
    /** Tier and length as last read during the run; DEFEAT clears the Kuudra state before we see it. */
    private int lastTier;
    private long lastRunMs;

    private KuudraProfitTracker() {
        ProfileContext.getInstance().register(this);
    }

    public static synchronized KuudraProfitTracker getInstance() {
        if (instance == null) {
            instance = new KuudraProfitTracker();
        }
        return instance;
    }

    /** Whether the probe wants every chat line; read by the chat prefilter. */
    public static boolean probing() {
        return System.currentTimeMillis() < probeUntil;
    }

    private static KuudraProfitSettings cfg() {
        return ConfigManager.getInstance().get().kuudraProfit;
    }

    public KuudraRunLedger ledger() {
        return ledger;
    }

    // ------------------------------------------------------------------ chat

    /** A chat line that passed the prefilter ({@link KuudraLoot#candidate} or the probe). */
    public void onChat(String plain) {
        if (plain == null || !cfg().enabled || plain.startsWith("[SBS]")) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            if (now < probeUntil) {
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][KuudraProfit] probe: {}", plain);
            }
            lastLineAt = now;
            book(parser.accept(plain), now);
        } catch (RuntimeException e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][KuudraProfit] chat line not processed: {}", plain, e);
        }
    }

    private void book(List<KuudraLoot.Event> events, long now) {
        for (KuudraLoot.Event event : events) {
            KuudraTracker kuudra = KuudraTracker.getInstance();
            int tier = kuudra.tier() > 0 ? kuudra.tier() : lastTier;
            if (event instanceof KuudraLoot.RunEnded) {
                if (!armed) {
                    continue;      // a repeat, or DEFEAT / KUUDRA DOWN said outside a run
                }
                armed = false;
                probeUntil = now + PROBE_MS;
            } else if (event instanceof KuudraLoot.RewardBlock) {
                if (!kuudra.inHollow()) {
                    continue;      // the same header outside Kuudra is not this run's chest
                }
                probeUntil = now + PROBE_MS;
            }
            long duration = kuudra.runMs() > 0 ? kuudra.runMs() : lastRunMs;
            KuudraRun run = ledger.apply(event, tier, duration, now, valuation);
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][KuudraProfit] {} -> T{} run profit={}{}", event, tier,
                    run == null ? "-" : run.profit(), run != null && run.incomplete() ? "+" : "");
            dirty(event instanceof KuudraLoot.RunEnded, now);
        }
    }

    // ------------------------------------------------------------------ tick

    public void onClientTick() {
        if (!cfg().enabled) {
            return;
        }
        long now = System.currentTimeMillis();
        if (parser.blockOpen() && now - lastLineAt >= BLOCK_IDLE_MS) {
            book(parser.flush(), now);
        }
        KuudraTracker kuudra = KuudraTracker.getInstance();
        if (kuudra.running() && kuudra.phase() != KuudraPhase.DOWN) {
            armed = true;
            if (kuudra.tier() > 0) {
                lastTier = kuudra.tier();
            }
            lastRunMs = kuudra.runMs();
        }
        if (throttle.pending(now)) {
            save(now);
        }
    }

    /** Lobby change, island change, disconnect: a half-read block is finished, the file written. */
    public void onWorldChange() {
        long now = System.currentTimeMillis();
        book(parser.flush(), now);
        armed = false;
        if (throttle.dirty()) {
            save(now);
        }
    }

    public void resetSession() {
        ledger.resetSession(System.currentTimeMillis());
    }

    /** History and totals of this profile; called after the player confirmed. */
    public void resetAll() {
        ledger.resetAll();
        save(System.currentTimeMillis());
    }

    // ------------------------------------------------------------------ prices

    private static final class Valuation implements KuudraRunLedger.Valuation {
        @Override
        public Long unitPrice(String name) {
            boolean sellOffer = cfg().priceSide == 1;
            for (String id : DungeonChestValue.bazaarIdCandidates(name)) {
                Long bz = bazaar(id, sellOffer);
                if (bz != null) {
                    return bz;
                }
            }
            SkyBlockItemCatalog.Entry entry = SkyBlockItemCatalog.getInstance().byName(name);
            if (entry != null) {
                Long bz = bazaar(entry.id, sellOffer);
                if (bz != null) {
                    return bz;
                }
                Long lbin = LbinCache.getInstance().getLbin(entry.id);
                if (lbin != null && lbin > 0) {
                    return lbin;
                }
            }
            String key = SkyblockItem.normalizeName(name);
            Long lbin = key.isEmpty() ? null : LbinCache.getInstance().getLbin(key);
            return lbin != null && lbin > 0 ? lbin : null;
        }

        /** What buying one costs today: the Bazaar's instant-buy price, else lowest BIN. */
        @Override
        public Long keyPrice(int tier) {
            String id = InstanceIds.kuudraKey(tier);
            if (id == null) {
                return null;
            }
            BazaarPriceCache.BzPrice price = BazaarPriceCache.getInstance().priceOf(id);
            if (price != null && price.buy() > 0) {
                return price.buy();
            }
            Long lbin = LbinCache.getInstance().getLbin(id);
            return lbin != null && lbin > 0 ? lbin : null;
        }

        @Override
        public boolean keyAtPurchasePrice() {
            return cfg().keyAtPurchasePrice;
        }

        private static Long bazaar(String id, boolean sellOffer) {
            BazaarPriceCache.BzPrice price = BazaarPriceCache.getInstance().priceOf(id);
            if (price == null) {
                return null;
            }
            long value = sellOffer ? price.buy() : price.sell();
            return value > 0 ? value : null;
        }
    }

    // ------------------------------------------------------------------ persistence

    private void dirty(boolean oneShot, long now) {
        if (throttle.request(oneShot, now)) {
            save(now);
        }
    }

    /** JSON built on the client thread, written on the ordered IO thread. */
    private void save(long now) {
        throttle.written(now);
        ProfileContext context = ProfileContext.getInstance();
        if (!context.known() || ledger.readOnly()) {
            return;
        }
        String json = ledger.toJson();
        Path path = context.file(FILE);
        SbsExecutors.io().execute(() -> {
            try {
                SBSFiles.ensureParent(path);
                Files.writeString(path, json);
            } catch (IOException e) {
                SkyblockSimplifiedSBS.LOGGER.warn("[SBS][KuudraProfit] could not write {}", path, e);
            }
        });
    }

    @Override
    public void flushProfile() {
        if (throttle.dirty()) {
            save(System.currentTimeMillis());
        }
    }

    @Override
    public void reloadProfile() {
        Path path = ProfileContext.getInstance().file(FILE);
        String json = null;
        try {
            if (Files.exists(path)) {
                json = Files.readString(path);
            }
        } catch (IOException e) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][KuudraProfit] {} unreadable, starting empty", path, e);
        }
        String problem = ledger.load(json);
        if (problem != null) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][KuudraProfit] {} not used: {}", path, problem);
        }
    }
}
