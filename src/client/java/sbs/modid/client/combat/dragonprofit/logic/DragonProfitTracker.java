/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.dragonprofit.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.async.SbsExecutors;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.ProfileContext;
import sbs.modid.client.core.config.ProfileScopedStore;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.core.item.SkyblockItem;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.economy.prices.BazaarPriceCache;
import sbs.modid.client.economy.prices.LbinCache;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemCatalog;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dragon Profit: the current End dragon fight ({@link DragonFightLedger}) and the session's and
 * profile's all-time {@link DragonStats}.
 *
 * <p>Where the loot reaches you on 26.2 is not captured yet, so both channels book: positive
 * inventory deltas of dragon loot against a baseline taken at "DRAGON DOWN!", and "+N" lines in the
 * hover of a "[Sacks]" message. Both go through the {@link DragonFightLedger#isDragonLoot} whitelist.
 *
 * <h2>The probe</h2>
 *
 * <p>From the 8th eye ("Brace yourselves") until the loot window closes, every chat line, every
 * sack hover line and every inventory gain is logged as {@code [SBS][Dragon] ...}. Outside that, every
 * dragon-shaped line the parser did not read is logged as {@code unread:}.
 */
public final class DragonProfitTracker implements ProfileScopedStore {

    private static final DragonProfitTracker INSTANCE = new DragonProfitTracker();

    private static final String FILE = "dragon_profit.json";
    /** How often the inventory is compared while the loot window is open. */
    private static final long INVENTORY_MS = 500L;
    /** The probe's ceiling if a fight is summoned but never goes down (lobby left, etc.). */
    private static final long PROBE_MAX_MS = 10 * 60_000L;
    /** One hover line of a "[Sacks]" message: "+3 Wise Dragon Fragment (Dragon Sack)". */
    private static final Pattern SACK_LINE = Pattern.compile("^\\s*([+-][\\d,]+) (.+?) \\(([^)]+)\\)\\s*$");

    private final DragonFightLedger fight = new DragonFightLedger();
    private volatile DragonStats session = new DragonStats();
    private volatile DragonStats allTime = new DragonStats();
    private volatile boolean loaded;
    private volatile int version;

    private long probeUntil;
    private Map<String, Integer> baseline;
    private final Map<String, Integer> booked = new HashMap<>();
    private long lastInventoryAt;
    private boolean wasInEnd;

    private DragonProfitTracker() {
        ProfileContext.getInstance().register(this);
    }

    public static DragonProfitTracker getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.DragonProfitSettings cfg() {
        return ConfigManager.getInstance().get().dragonProfit;
    }

    /** Bumped on every change the card shows. */
    public int version() {
        return version;
    }

    public DragonStats session() {
        return session;
    }

    public DragonStats allTime() {
        ensureLoaded();
        return allTime;
    }

    /** Eyes you placed in the running fight, 0 when none is running. */
    public int currentEyes() {
        return fight.eyes();
    }

    /**
     * A chat line. No dispatch prefilter, because the probe window needs every line; outside it the
     * first test here drops anything that is neither dragon-shaped nor a "[Sacks]" message.
     */
    public void onChat(String plain, String raw, Component message) {
        if (!cfg().enabled) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean probing = now < probeUntil;
        if (!probing && !DragonParser.candidate(plain) && !plain.startsWith("[Sacks]")) {
            return;
        }
        String clean = DragonParser.clean(raw);
        if (probing) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Dragon] chat: {}", clean);
        }
        if (clean.startsWith("[Sacks]")) {
            onSacks(message, now, probing);
            return;
        }
        if (!probing && !DragonParser.candidate(clean)) {
            return;
        }
        DragonParser.Event event = DragonParser.parse(raw);
        if (event == null) {
            if (!probing && DragonParser.candidate(clean)) {
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Dragon] unread: {}", clean);
            }
            return;
        }
        boolean wasDown = fight.down();
        int eyesBefore = fight.eyes();
        fight.onEvent(event, selfName(), now);
        if (event.type() == DragonParser.Type.EYE_PLACED && event.count() == 8) {
            probeUntil = now + PROBE_MAX_MS;
        }
        if (!wasDown && fight.down()) {
            probeUntil = now + DragonFightLedger.LOOT_WINDOW_MS;
            baseline = inventory();
            booked.clear();
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Dragon] down {} | eyes {}", fight.type(), fight.eyes());
        }
        if (fight.eyes() != eyesBefore || !wasDown && fight.down()) {
            version++;
        }
    }

    private void onSacks(Component message, long now, boolean probing) {
        if (!fight.lootWindowOpen(now) && !probing) {
            return;
        }
        StringBuilder hover = new StringBuilder();
        collectHoverText(message, hover);
        Map<String, Integer> perItem = new HashMap<>();
        for (String line : hover.toString().split("\n")) {
            String plain = DragonParser.clean(line);
            if (plain.isEmpty()) {
                continue;
            }
            if (probing) {
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Dragon] sack: {}", plain);
            }
            Matcher m = SACK_LINE.matcher(plain);
            if (!m.matches() || m.group(1).startsWith("-")) {
                continue;
            }
            String id = idForName(m.group(2));
            if (id != null) {
                // The hover can sit on several segments of one message: max, not sum, per item.
                perItem.merge(id, Integer.parseInt(m.group(1).substring(1).replace(",", "")), Math::max);
            }
        }
        perItem.forEach((id, count) -> fight.onGain(id, count, now));
    }

    /** Every client tick: the inventory diff during the loot window and the fight close. */
    public void onClientTick() {
        if (!cfg().enabled) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean inEnd = SkyBlockLocation.onIsland("The End");
        if (wasInEnd && !inEnd && fight.active()) {
            DragonFightLedger.Result left = fight.leave(DragonProfitTracker::price);
            if (left != null) {
                booked(left);
            } else {
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Dragon] left The End before the dragon went down");
                version++;
            }
            probeUntil = 0L;
        }
        wasInEnd = inEnd;
        if (!fight.down()) {
            return;
        }
        if (fight.lootWindowOpen(now) && baseline != null && now - lastInventoryAt >= INVENTORY_MS) {
            lastInventoryAt = now;
            Map<String, Integer> current = inventory();
            for (Map.Entry<String, Integer> e : current.entrySet()) {
                int gained = e.getValue() - baseline.getOrDefault(e.getKey(), 0);
                int already = booked.getOrDefault(e.getKey(), 0);
                if (gained > already) {
                    SkyblockSimplifiedSBS.LOGGER.info("[SBS][Dragon] inventory +{} {}", gained - already, e.getKey());
                    fight.onGain(e.getKey(), gained - already, now);
                    booked.put(e.getKey(), gained);
                }
            }
        }
        DragonFightLedger.Result done = fight.tick(now, DragonProfitTracker::price);
        if (done != null) {
            booked(done);
        }
    }

    private void booked(DragonFightLedger.Result result) {
        baseline = null;
        booked.clear();
        StringBuilder prices = new StringBuilder();
        for (var drop : result.drops().entrySet()) {
            prices.append(prices.length() == 0 ? "" : ", ").append(drop.getKey()).append('=').append(price(drop.getKey()));
        }
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Dragon] fight {} eyes {} dmg {} #{} drops {} value {} cost {} unit prices {{}}",
                result.type(), result.eyes(), result.damage(), result.position(), result.drops(),
                result.dropValue(), result.eyeCost(), prices);
        ensureLoaded();
        session.add(result);
        allTime.add(result);
        version++;
        save();
    }

    /**
     * Unit price of a drop: its id first, then the price keys its stack resolved to when it was seen
     * in the inventory - the same {@code ItemPriceKey.keysFor} path the Sacrifice Altar values with.
     * The Aspect of the Dragons carries the id {@code ASPECT_OF_THE_DRAGON} while the price cache
     * knows it as {@code ASPECT_OF_THE_DRAGONS}; priced by id alone it was worth 0 (2026-10-18).
     */
    static Long price(String id) {
        return firstPriced(id, PRICE_KEYS.getOrDefault(id, List.of()), DragonProfitTracker::priceOf);
    }

    /** The first non-null, positive price of {@code id}, else of each key in order. Pure, for the test. */
    static Long firstPriced(String id, List<String> keys, java.util.function.Function<String, Long> priceOf) {
        Long direct = priceOf.apply(id);
        if (direct != null && direct > 0) {
            return direct;
        }
        for (String key : keys) {
            if (key.equals(id)) {
                continue;
            }
            Long p = priceOf.apply(key);
            if (p != null && p > 0) {
                return p;
            }
        }
        return null;
    }

    /** Price keys per item id, taken from the first stack of it seen in the inventory. Client thread. */
    private static final Map<String, List<String>> PRICE_KEYS = new HashMap<>();

    /** Unit price of one key: Bazaar (side per settings), else lowest BIN; {@code null} when unknown. */
    private static Long priceOf(String id) {
        SBSConfig.DragonProfitSettings cfg = cfg();
        BazaarPriceCache.BzPrice bz = BazaarPriceCache.getInstance().priceOf(id);
        if (bz != null) {
            boolean sellOffer = cfg.sellOffer && !(cfg.fragmentsAtInstantSell && id.endsWith("_FRAGMENT"));
            long value = sellOffer ? bz.buy() : bz.sell();
            if (value > 0) {
                return value;
            }
        }
        Long lbin = LbinCache.getInstance().getLbin(id);
        return lbin != null && lbin > 0 ? lbin : null;
    }

    private static String selfName() {
        var player = Minecraft.getInstance().player;
        return player == null ? null : player.getGameProfile().name();
    }

    private static String idForName(String name) {
        SkyBlockItemCatalog.Entry entry = SkyBlockItemCatalog.getInstance().byName(name);
        return entry == null ? null : entry.id;
    }

    /** SkyBlock id to total count over the main inventory and hotbar. */
    private static Map<String, Integer> inventory() {
        Map<String, Integer> counts = new HashMap<>();
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return counts;
        }
        Inventory inventory = player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            String id = stack.isEmpty() ? null : SkyblockItem.id(stack);
            if (id != null) {
                counts.merge(id, stack.getCount(), Integer::sum);
                PRICE_KEYS.computeIfAbsent(id, k -> sbs.modid.client.economy.prices.ItemPriceKey.keysFor(stack));
            }
        }
        return counts;
    }

    private static void collectHoverText(Component component, StringBuilder out) {
        if (component == null) {
            return;
        }
        if (component.getStyle().getHoverEvent() instanceof HoverEvent.ShowText(Component text)) {
            out.append(text.getString()).append('\n');
        }
        for (Component sibling : component.getSiblings()) {
            collectHoverText(sibling, out);
        }
    }

    public void resetSession() {
        session = new DragonStats();
        version++;
    }

    /** Clears this profile's all-time totals too. */
    public void resetAllTime() {
        ensureLoaded();
        allTime = new DragonStats();
        session = new DragonStats();
        version++;
        save();
    }

    private void ensureLoaded() {
        if (!loaded) {
            reloadProfile();
        }
    }

    @Override
    public synchronized void reloadProfile() {
        DragonStats read = new DragonStats();
        try {
            Path path = ProfileContext.getInstance().file(FILE);
            if (Files.isRegularFile(path)) {
                try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    DragonStats parsed = SBSFiles.GSON.fromJson(reader, DragonStats.class);
                    if (parsed != null) {
                        read = parsed;
                    }
                }
            }
        } catch (Exception e) {
            // A corrupt file is logged, not fatal; it stays on disk and memory starts empty.
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Dragon] could not read {} ({})", FILE, e.toString());
        }
        allTime = read;
        session = new DragonStats();
        loaded = true;
        version++;
    }

    @Override
    public void flushProfile() {
        if (loaded) {
            save();
        }
    }

    /** JSON built here, written on the ordered IO thread. */
    private void save() {
        if (!loaded || !ProfileContext.getInstance().known()) {
            return;
        }
        String json = SBSFiles.GSON.toJson(allTime);
        Path path = ProfileContext.getInstance().file(FILE);
        SbsExecutors.io().execute(() -> {
            try {
                Files.createDirectories(path.getParent());
                Files.writeString(path, json, StandardCharsets.UTF_8);
            } catch (Exception e) {
                SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Dragon] could not write {} ({})", FILE, e.toString());
            }
        });
    }
}
