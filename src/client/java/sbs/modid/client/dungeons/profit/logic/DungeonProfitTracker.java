/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.profit.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.async.SbsExecutors;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.ProfileContext;
import sbs.modid.client.core.config.ProfileScopedStore;
import sbs.modid.client.core.config.SBSConfig.DungeonProfitSettings;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.core.config.SaveThrottle;
import sbs.modid.client.core.item.SkyblockItem;
import sbs.modid.client.core.tracker.TrackerStore;
import sbs.modid.client.core.util.PlainText;
import sbs.modid.client.dungeons.chest.DungeonChestValue;
import sbs.modid.client.dungeons.chest.KismetPrice;
import sbs.modid.client.dungeons.profit.logic.DungeonLoot.ChestKind;
import sbs.modid.client.dungeons.profit.model.DungeonRun;
import sbs.modid.client.dungeons.run.logic.DungeonStateManager;
import sbs.modid.client.economy.prices.BazaarPriceCache;
import sbs.modid.client.economy.prices.LbinCache;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemCatalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Dungeon Profit Tracker's connection to the game: chat lines in, the dungeon state and open
 * chest menus read, the profile's {@code dungeon_profit.json} written. The bookkeeping is
 * {@link RunLedger}; the line shapes are {@link DungeonLoot}.
 *
 * <p>Read-only: it reads chat, the scoreboard-derived dungeon state and the tooltips of a menu the
 * player opened. It never clicks, opens or sends anything, and stores no other player's name.
 */
public final class DungeonProfitTracker implements ProfileScopedStore {

    private static final String FILE = "dungeon_profit.json";
    private static final String TRACKER = "dungeonprofit";
    /** A reward block with no further line for this long is complete. */
    private static final long BLOCK_IDLE_MS = 750L;
    /** A chest cost read from a menu is trusted for a claim this soon after. */
    private static final long COST_FRESH_MS = 10 * 60_000L;
    /** {@code Catacombs - Floor IV} (CONFIRMED title, 2026-07-26); the Master Mode form is a guess. */
    private static final Pattern CROESUS_PAGE = Pattern.compile("(?i)(master)?.*catacombs - floor ([IVX]+)");
    private static final Pattern NON_ID = Pattern.compile("[^A-Z0-9]+");
    /** A drop-key press excuses inventory shrinks this long (the server's update lags the key). */
    private static final long DROP_GRACE_MS = 1_000L;

    private static DungeonProfitTracker instance;

    private final DungeonLoot parser = new DungeonLoot();
    private final RunLedger ledger = new RunLedger(System.currentTimeMillis());
    private final SaveThrottle throttle = new SaveThrottle();
    private final Map<ChestKind, long[]> costs = new EnumMap<>(ChestKind.class);   // {cost, seenAt}
    private final Valuation valuation = new Valuation();

    private final PotionWatch potionWatch = new PotionWatch();
    /** Potion id -> unit price of this session's last {@code "You bought"} line. */
    private final Map<String, Long> purchased = new HashMap<>();

    private long lastLineAt;
    private int potionInventoryState = Integer.MIN_VALUE;
    private long dropUntil;
    private AbstractContainerMenu scannedMenu;
    private int scannedState = Integer.MIN_VALUE;

    private DungeonProfitTracker() {
        ledger.onRare(name -> TrackerStore.record(TRACKER, NON_ID.matcher(name.toUpperCase(Locale.ROOT))
                .replaceAll("_"), 1));
        ProfileContext.getInstance().register(this);
    }

    public static synchronized DungeonProfitTracker getInstance() {
        if (instance == null) {
            instance = new DungeonProfitTracker();
        }
        return instance;
    }

    private static DungeonProfitSettings cfg() {
        return ConfigManager.getInstance().get().dungeonProfit;
    }

    public RunLedger ledger() {
        return ledger;
    }

    // ------------------------------------------------------------------ chat

    /** A chat line that passed the prefilter ({@link DungeonLoot#candidate}). */
    public void onChat(String plain) {
        if (plain == null || !cfg().enabled || plain.startsWith("[SBS]")) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            lastLineAt = now;
            book(parser.accept(plain, ownName()), now);
        } catch (RuntimeException e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][DungeonProfit] chat line not processed: {}", plain, e);
        }
    }

    private void book(List<DungeonLoot.Event> events, long now) {
        for (DungeonLoot.Event event : events) {
            if (event instanceof DungeonLoot.PotionBought bought && bought.id() != null && bought.coins() > 0) {
                purchased.put(bought.id(), bought.coins() / Math.max(1, bought.count()));
            } else if (event instanceof DungeonLoot.PotionsLocked && potionWatch.armed()) {
                potionWatch.disarm();
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][DungeonProfit] potion window closed, {} drunk",
                        ledger.current() == null ? 0 : ledger.current().potions);
            }
            if (ledger.apply(event, now, valuation)) {
                if (event instanceof DungeonLoot.RewardBlock block) {
                    SkyblockSimplifiedSBS.LOGGER.info("[SBS][DungeonProfit] {} chest, {} items, run open={}",
                            block.kind(), block.items().size(), ledger.current() != null);
                }
                dirty(false, now);
            }
        }
    }

    private static String ownName() {
        var player = Minecraft.getInstance().player;
        return player == null ? "" : player.getGameProfile().name();
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
        DungeonStateManager state = DungeonStateManager.getInstance();
        if (state.inDungeon()) {
            if (ledger.current() == null || ledger.current().finished()) {
                ledger.startRun(state.floorLabel(), now);
                potionWatch.arm();
                dirty(false, now);
            } else {
                ledger.floorKnown(state.floorLabel());
            }
            if (potionWatch.armed()) {
                watchPotions(now);
            }
        } else if (potionWatch.armed()) {
            potionWatch.disarm();
        }
        if (!state.inDungeon() && ledger.current() != null && !parser.blockOpen()) {
            DungeonRun run = ledger.endRun(now, cfg().userCostPerRun);
            if (run != null) {
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][DungeonProfit] run finished: floor={} profit={} incomplete={}",
                        run.floor, run.profit(), run.incomplete());
            }
            dirty(true, now);
        }
        scanMenu(now);
        if (throttle.pending(now)) {
            save(now);
        }
    }

    /**
     * The inventory's Dungeon Potions, read only when the inventory changed and only between entry
     * and the start line (a few seconds per run), handed to {@link PotionWatch}. Any open screen or
     * the drop key makes a shrink a move, not a drink.
     */
    private void watchPotions(long now) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        if (mc.options.keyDrop.isDown()) {
            dropUntil = now + DROP_GRACE_MS;
        }
        boolean blocked = sbs.modid.client.core.api.ScreenAccess.current() != null || now < dropUntil;
        int stateId = mc.player.inventoryMenu.getStateId();
        if (stateId == potionInventoryState && !blocked) {
            return;
        }
        potionInventoryState = stateId;
        Map<String, Integer> counts = new HashMap<>();
        var inventory = mc.player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            String id = PotionWatch.idOf(PlainText.strip(stack.getHoverName().getString()));
            if (id != null) {
                counts.merge(id, stack.getCount(), Integer::sum);
            }
        }
        for (String id : potionWatch.observe(counts, blocked)) {
            if (ledger.potionConsumed(id, valuation)) {
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][DungeonProfit] potion: {} consumed before start", id);
                dirty(false, now);
            }
        }
    }

    /**
     * Reads chest costs and the Croesus page floor from an open menu, once per content change. The
     * cost is the same "Cost" line {@link DungeonChestValue} reads (CONFIRMED in the reward chest
     * preview, 2026-10-04).
     */
    private void scanMenu(long now) {
        if (!(sbs.modid.client.core.api.ScreenAccess.current() instanceof AbstractContainerScreen<?> screen)) {
            scannedMenu = null;
            return;
        }
        AbstractContainerMenu menu = screen.getMenu();
        if (menu == scannedMenu && menu.getStateId() == scannedState) {
            return;
        }
        scannedMenu = menu;
        scannedState = menu.getStateId();
        String title = PlainText.strip(screen.getTitle().getString()).trim();
        Matcher page = CROESUS_PAGE.matcher(title);
        if (page.find()) {
            ledger.croesusPage((page.group(1) != null ? "M" : "F") + roman(page.group(2)), now);
        }
        ChestKind titleKind = ChestKind.of(title);
        int upper = Math.max(0, menu.getItems().size() - 36);
        for (int i = 0; i < upper; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (stack.isEmpty()) {
                continue;
            }
            var lore = stack.get(DataComponents.LORE);
            if (lore == null) {
                continue;
            }
            List<String> lines = new ArrayList<>(lore.lines().size());
            for (var line : lore.lines()) {
                lines.add(PlainText.strip(line.getString()).trim());
            }
            DungeonChestValue.Listing listing = DungeonChestValue.parse(lines);
            if (listing == null) {
                continue;
            }
            ChestKind kind = titleKind;
            if (kind == null) {
                String name = PlainText.strip(stack.getHoverName().getString()).trim();
                int space = name.indexOf(' ');
                kind = ChestKind.of(space > 0 ? name.substring(0, space) : name);
            }
            if (kind != null) {
                costs.put(kind, new long[] {listing.cost(), now});
            }
        }
    }

    static int roman(String text) {
        int total = 0;
        int previous = 0;
        for (int i = text.length() - 1; i >= 0; i--) {
            int value = switch (Character.toUpperCase(text.charAt(i))) {
                case 'I' -> 1;
                case 'V' -> 5;
                case 'X' -> 10;
                default -> 0;
            };
            total += value < previous ? -value : value;
            previous = Math.max(previous, value);
        }
        return total;
    }

    /** Lobby change, island change, disconnect: a half-read block is finished, the file written. */
    public void onWorldChange() {
        long now = System.currentTimeMillis();
        book(parser.flush(), now);
        scannedMenu = null;
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

    private final class Valuation implements RunLedger.Valuation {
        private final java.util.Set<String> loggedUnpriced = new java.util.HashSet<>();

        @Override
        public Long unitPrice(String name) {
            Long price = lookup(name);
            if (price == null && loggedUnpriced.add(name)) {
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][DungeonProfit] unpriced: {}", name);
            }
            return price;
        }

        private Long lookup(String name) {
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

        private Long bazaar(String id, boolean sellOffer) {
            BazaarPriceCache.BzPrice price = BazaarPriceCache.getInstance().priceOf(id);
            if (price == null) {
                return null;
            }
            long value = sellOffer ? price.buy() : price.sell();
            return value > 0 ? value : null;
        }

        @Override
        public Long kismetPrice() {
            return KismetPrice.cost();
        }

        @Override
        public Long chestCost(ChestKind kind) {
            long[] seen = costs.get(kind);
            return seen != null && System.currentTimeMillis() - seen[1] <= COST_FRESH_MS ? seen[0] : null;
        }

        /** Market: the Bazaar by id, then by name, on the price side setting; see {@link PotionWatch#price}. */
        @Override
        public Long potionPrice(String id) {
            Long market = bazaar(id, cfg().priceSide == 1);
            if (market == null) {
                market = unitPrice(PotionWatch.nameOf(id));
            }
            return PotionWatch.price(cfg().potionCostSide == 1, purchased.get(id), market);
        }

        @Override
        public long userCostPerRun() {
            return cfg().userCostPerRun;
        }

        @Override
        public boolean countEveryoneEssence() {
            return cfg().countEveryoneEssence;
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
                SkyblockSimplifiedSBS.LOGGER.warn("[SBS][DungeonProfit] could not write {}", path, e);
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
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][DungeonProfit] {} unreadable, starting empty", path, e);
        }
        String problem = ledger.load(json);
        if (problem != null) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][DungeonProfit] {} not used: {}", path, problem);
        }
    }
}
