/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.farming.net;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.ProfileContext;
import sbs.modid.client.core.config.ProfileScopedStore;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.economy.itemvalue.ItemAppraisal;
import sbs.modid.client.economy.prices.BazaarPriceCache;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemCatalog;
import sbs.modid.client.skills.SkillIslands;
import sbs.modid.client.skills.collection.CollectionCatalog;
import sbs.modid.client.skills.collection.CollectionTracker;
import sbs.modid.client.skills.farming.model.CropBlocks;
import sbs.modid.client.skills.farming.model.CropType;
import sbs.modid.client.skills.farming.model.FarmingItems;
import sbs.modid.client.skills.garden.logic.SprayTracker;
import sbs.modid.client.skills.garden.logic.VisitorShoppingList;
import sbs.modid.client.skills.garden.model.SprayMaterial;
import sbs.modid.client.skills.garden.pests.PestProfitTracker;
import sbs.modid.client.skills.garden.ui.VisitorShoppingHud;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Feeds the {@link FarmingNetLedger} from the trackers that already count each source. It parses
 * no chat and no menus of its own:
 *
 * <ul>
 *   <li><b>Crops</b>: {@link CollectionTracker#sessionGains()}, the counter the Farming Tracker and
 *       the Farming Session Summary price - read as the increase over a baseline taken when this
 *       ledger starts, so gains from before a reset are not counted twice.</li>
 *   <li><b>Pests</b>: the Pest Profit session ledger's value, priced its way; the same baseline.</li>
 *   <li><b>Visitors</b>: the offer {@code VisitorOfferStore} recorded from the visitor's menu, handed
 *       over at the accept line just before the store drops it.</li>
 *   <li><b>Sprays</b>: a new spray going active on {@link SprayTracker} uses one of its material
 *       (ESTIMATED - one item per spray).</li>
 *   <li><b>Active time</b>: crop breaks with a farming tool on a farming island, the session
 *       summary's rule.</li>
 * </ul>
 *
 * <p>None of those trackers publishes events, so the levels are polled once a second. The session is
 * not persisted: it starts at game start, on a profile switch and on the reset button.
 */
public final class FarmingNetTracker implements ProfileScopedStore {

    private static final FarmingNetTracker INSTANCE = new FarmingNetTracker();
    private static final long POLL_MS = 1000L;

    private FarmingNetLedger ledger;
    private long ledgerPauseMs;
    /** Collection id -> session gain already booked (or present when the ledger started). */
    private final Map<String, Long> cropBaseline = new HashMap<>();
    private boolean cropsBaselined;
    private long pestBaseline = -1;
    private boolean sprayKnown;
    private SprayMaterial lastSpray;
    private long lastPollAt;

    private FarmingNetTracker() {
        ProfileContext.getInstance().register(this);
    }

    public static FarmingNetTracker getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.GardenHelpersSettings cfg() {
        return ConfigManager.getInstance().get().gardenHelpers;
    }

    /** The ledger, rebuilt (empty) only when it is empty and the pause threshold changed. */
    public FarmingNetLedger ledger() {
        long pause = Math.max(5, cfg().farmingSessionPauseSeconds) * 1000L;
        if (ledger == null || (pause != ledgerPauseMs && ledger.isEmpty())) {
            ledger = new FarmingNetLedger(pause);
            ledgerPauseMs = pause;
        }
        return ledger;
    }

    // ------------------------------------------------------------------ hooks

    /** A BlockBreakEvents listener: a crop broken with a farming tool is active farming time. */
    public void onBlockBroken(BlockPos pos, BlockState state) {
        if (!cfg().farmingNet || !SkillIslands.onFarmingIsland()) {
            return;
        }
        var player = Minecraft.getInstance().player;
        if (player == null || !FarmingItems.isFarmingTool(player.getMainHandItem())
                || CropBlocks.of(state, CropType.forHeldTool()) == null) {
            return;
        }
        ledger().onFarmed(System.currentTimeMillis());
    }

    /**
     * The accept line, from {@code VisitorOfferStore} before it forgets the offer. {@code offer} is
     * null when the visitor's menu was never opened - then the visit is counted without values.
     */
    public void onVisitorServed(String visitor, VisitorShoppingList.Offer offer) {
        if (!cfg().farmingNet) {
            return;
        }
        if (offer == null) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][FarmNet] '{}' served with no recorded offer - unpriced", visitor);
            ledger().onVisitor(0, false, -1);
            return;
        }
        VisitorShoppingList.VisitorCost cost = VisitorShoppingList
                .visitorCosts(List.of(offer), VisitorShoppingHud::unitPrice).get(0);
        ledger().onVisitor(cost.cost(), cost.fullyPriced(), offer.copper());
    }

    /** Client tick (farming islands, deferrable): polls the sources at most once a second. */
    public void onClientTick() {
        long now = System.currentTimeMillis();
        if (!cfg().farmingNet || now - lastPollAt < POLL_MS) {
            return;
        }
        lastPollAt = now;
        FarmingNetLedger l = ledger();
        pollCrops(l);
        pollPests(l);
        pollSpray(l);
    }

    public void reset() {
        if (ledger != null) {
            ledger.reset();
        }
        cropBaseline.clear();
        cropsBaselined = false;
        pestBaseline = -1;
        sprayKnown = false;
        lastSpray = null;
    }

    @Override
    public void reloadProfile() {
        reset();   // the readings belong to the profile being left
    }

    @Override
    public void flushProfile() {
        // nothing persisted
    }

    // ------------------------------------------------------------------ sources

    private void pollCrops(FarmingNetLedger l) {
        Map<String, Long> gains = CollectionTracker.getInstance().sessionGains();
        for (Map.Entry<String, Long> e : gains.entrySet()) {
            CropType crop = cropFor(e.getKey());
            if (crop == null) {
                continue;
            }
            long now = e.getValue();
            Long before = cropBaseline.put(e.getKey(), now);
            if (!cropsBaselined) {
                continue;   // first reading: the baseline, not income
            }
            long delta = now - (before == null ? 0 : before);
            if (delta > 0) {
                l.addCrops(crop.bazaarId(), delta);
            }
        }
        cropsBaselined = true;
    }

    private void pollPests(FarmingNetLedger l) {
        long value = Math.round(PestProfitTracker.getInstance().session()
                .value(PestProfitTracker::price, cfg().pestProfitTrapLoot));
        if (pestBaseline < 0 || value < pestBaseline) {
            pestBaseline = value;   // first reading, or the pest session was reset under us
            l.setPestCoins(0);
            return;
        }
        l.setPestCoins(value - pestBaseline);
    }

    private void pollSpray(FarmingNetLedger l) {
        SprayMaterial active = SprayTracker.getInstance().active();
        if (sprayKnown && active != null && active != lastSpray) {
            l.addConsumable(active.itemId(), 1);
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][FarmNet] spray started: 1 {}", active.itemId());
        }
        sprayKnown = true;
        lastSpray = active;
    }

    static CropType cropFor(String collectionId) {
        String key = CollectionCatalog.normalizeKey(collectionId);
        for (CropType crop : CropType.values()) {
            if (CollectionCatalog.normalizeKey(crop.bazaarId()).equals(key)) {
                return crop;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ prices

    /** The card's pricing, from the settings - every value here belongs in the card's key. */
    public static FarmingNetLedger.Pricing pricing() {
        SBSConfig.GardenHelpersSettings cfg = cfg();
        CropPriceSource source = cfg.farmingNetPriceSource;
        return new FarmingNetLedger.Pricing(id -> cropPrice(id, source), FarmingNetTracker::costPrice,
                cfg.farmingNetCoinsPerCopper, cfg.farmingNetVisitors, cfg.farmingNetExtraCostPerHour);
    }

    /**
     * A crop's unit price: a Bazaar sell order lists at about the lowest current sell offer, which is
     * the instant-buy price; selling instantly gets the instant-sell price; NPC is the catalogue's.
     */
    static double cropPrice(String bazaarId, CropPriceSource source) {
        if (source == CropPriceSource.NPC) {
            SkyBlockItemCatalog.Entry entry = SkyBlockItemCatalog.getInstance().byId(bazaarId);
            return entry == null ? 0 : entry.npcSellPrice;
        }
        BazaarPriceCache.BzPrice price = BazaarPriceCache.getInstance().get(bazaarId);
        if (price == null) {
            return 0;
        }
        return source == CropPriceSource.SELL_ORDER ? price.buy() : price.sell();
    }

    /** What a consumable costs to replace: the Bazaar instant-buy, else lowest BIN. */
    static double costPrice(String itemId) {
        Long price = ItemAppraisal.price(itemId, ItemAppraisal.Side.BUY);
        return price == null ? 0 : price;
    }
}
