/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.sacrifice.logic;

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
import sbs.modid.client.economy.prices.ItemPriceKey;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Sacrifice Altar profit: the session's and the profile's all-time sacrifices, fed by
 * {@link SacrificeParser} and an inventory diff.
 *
 * <h2>The cost</h2>
 *
 * <p>The SACRIFICE line names the item but not its reforge, enchants or stars. So while in The End
 * the 36 main inventory slots are compared every tick (a reference and count check; a slot is only
 * re-read when its stack changed), and whatever leaves is valued at that moment and kept in a
 * {@link RemovalLog}. The SACRIFICE line claims the matching removal (one up to {@link RemovalLog#LOOKBACK_MS}
 * old - the altar menu sits between the item leaving and the line). When none turns up within
 * {@link RemovalLog#WINDOW_MS}, the base item is priced by name and the cost is marked approximate.
 *
 * <h2>Why not TrackerStore</h2>
 *
 * <p>As for Dice Profit: the card reads all-time counts back per profile, which the write-only
 * lifetime log cannot do. The all-time ledger is a profile file ({@value #FILE}).
 */
public final class SacrificeTracker implements ProfileScopedStore {

    private static final SacrificeTracker INSTANCE = new SacrificeTracker();

    private static final String FILE = "sacrifice_altar.json";
    public static final String ESSENCE_ID = "ESSENCE_DRAGON";
    private static final int SLOTS = 36;
    /** How long the card counts as "in The End" after the last End tick. */
    private static final long END_GRACE_MS = 2_000L;
    /** How long a settled sacrifice waits for its BONUS LOOT lines before it is committed. */
    private static final long BONUS_WAIT_MS = 1_000L;

    /** A sacrifice whose chat line arrived but whose cost is not settled yet. */
    private static final class Pending {
        final String item;
        final int essence;
        final long at;
        final List<SacrificeLedger.Bonus> bonuses = new ArrayList<>();
        Long cost;
        boolean approx;

        Pending(String item, int essence, long at) {
            this.item = item;
            this.essence = essence;
            this.at = at;
        }
    }

    private volatile SacrificeLedger session = new SacrificeLedger();
    private volatile SacrificeLedger allTime = new SacrificeLedger();
    private volatile boolean loaded;
    private volatile int version;
    private volatile long lastEndTick;

    private final RemovalLog removals = new RemovalLog();
    private Pending pending;

    private final ItemStack[] refs = new ItemStack[SLOTS];
    private final int[] counts = new int[SLOTS];
    private final List<SlotDiff.Slot<ItemStack>> before = new ArrayList<>(SLOTS);
    private boolean primed;

    private SacrificeTracker() {
        ProfileContext.getInstance().register(this);
    }

    public static SacrificeTracker getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.SacrificeAltarSettings cfg() {
        return ConfigManager.getInstance().get().sacrificeAltar;
    }

    /** Bumped on every change the card shows. */
    public int version() {
        return version;
    }

    /** Whether The End was ticked in the last couple of seconds. */
    public boolean inEnd() {
        return System.currentTimeMillis() - lastEndTick < END_GRACE_MS;
    }

    /** How far from the remembered altar spot the card still shows, in blocks. */
    static final int ALTAR_RADIUS = 20;

    /**
     * Whether the card belongs on screen: in The End and within {@link #ALTAR_RADIUS} of where the
     * last sacrifice happened. Before the first sacrifice the spot is unknown and the card hides -
     * it had nothing to show then anyway.
     */
    public boolean nearAltar() {
        SBSConfig.SacrificeAltarSettings c = cfg();
        var player = Minecraft.getInstance().player;
        return inEnd() && c.altarKnown && player != null
                && within(player.getX(), player.getY(), player.getZ(), c.altarX, c.altarY, c.altarZ, ALTAR_RADIUS);
    }

    /** Whether {@code (x,y,z)} is within {@code radius} of the block {@code (ax,ay,az)}. Pure, for the test. */
    static boolean within(double x, double y, double z, int ax, int ay, int az, int radius) {
        double dx = x - (ax + 0.5);
        double dy = y - (ay + 0.5);
        double dz = z - (az + 0.5);
        return dx * dx + dy * dy + dz * dz <= (double) radius * radius;
    }

    /**
     * Learns the altar's spot from where the player stands as a SACRIFICE line arrives - they are
     * at the altar then - and logs the sidebar zone, so the spot can later be replaced by a zone
     * check once its name is on record.
     */
    private void rememberAltar() {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        SBSConfig.SacrificeAltarSettings c = cfg();
        int x = player.getBlockX();
        int y = player.getBlockY();
        int z = player.getBlockZ();
        boolean moved = !c.altarKnown || !within(x, y, z, c.altarX, c.altarY, c.altarZ, 4);
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Sacrifice] sacrifice at {},{},{} zone='{}'{}", x, y, z,
                sbs.modid.client.core.location.SkyBlockLocation.zone(), moved ? " - altar spot saved" : "");
        if (moved) {
            c.altarKnown = true;
            c.altarX = x;
            c.altarY = y;
            c.altarZ = z;
            ConfigManager.getInstance().save();
        }
    }

    public SacrificeLedger session() {
        return session;
    }

    public SacrificeLedger allTime() {
        ensureLoaded();
        return allTime;
    }

    /** The unit price of an id on the configured side, or {@code null}. */
    public static Long price(String id) {
        return ItemAppraisal.price(id, cfg().sellSide ? ItemAppraisal.Side.SELL : ItemAppraisal.Side.BUY);
    }

    /** A chat line that passed the prefilter. */
    public void onChat(String line) {
        if (!cfg().enabled) {
            return;
        }
        SacrificeParser.Event event = SacrificeParser.parse(line);
        if (event == null) {
            String clean = SacrificeParser.clean(line);
            if (inEnd() && SacrificeParser.candidate(clean)) {
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Sacrifice] unread: {}", clean);
            }
            return;
        }
        long now = System.currentTimeMillis();
        switch (event.type()) {
            case SACRIFICE -> {
                rememberAltar();
                commitPending(true);
                pending = new Pending(event.item(), event.count(), now);
                settle(now);
            }
            case BONUS -> {
                if (!event.own()) {
                    return;
                }
                SacrificeLedger.Bonus bonus = new SacrificeLedger.Bonus(
                        SacrificeParser.itemId(event.item()), event.item(), event.count());
                if (pending != null) {
                    pending.bonuses.add(bonus);
                } else {
                    // The sacrifice was already committed; the drop still belongs to the totals.
                    ensureLoaded();
                    session.addBonus(bonus);
                    allTime.addBonus(bonus);
                    version++;
                    save();
                }
            }
            case REFUSED -> {
                // Nothing was sacrificed; whatever left the inventory just now was not this.
            }
        }
    }

    /** Every client tick in The End: the inventory diff and the pending sacrifice. */
    public void onClientTick() {
        if (!cfg().enabled) {
            return;
        }
        long now = System.currentTimeMillis();
        lastEndTick = now;
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        diffInventory(player.getInventory(), now);
        if (pending != null) {
            settle(now);
            if (pending != null) {
                long age = now - pending.at;
                // Bonus lines follow the SACRIFICE line within a tick or two; give them a second.
                if ((pending.cost != null && age > BONUS_WAIT_MS) || age > RemovalLog.WINDOW_MS) {
                    commitPending(true);
                }
            }
        }
    }

    private void diffInventory(Inventory inventory, long now) {
        boolean changed = false;
        List<SlotDiff.Slot<ItemStack>> after = new ArrayList<>(SLOTS);
        for (int i = 0; i < SLOTS; i++) {
            ItemStack stack = inventory.getItem(i);
            int count = stack.isEmpty() ? 0 : stack.getCount();
            if (primed && stack == refs[i] && count == counts[i]) {
                after.add(before.get(i));
                continue;
            }
            changed = true;
            refs[i] = stack;
            counts[i] = count;
            after.add(count == 0 ? new SlotDiff.Slot<>("", 0, null)
                    : new SlotDiff.Slot<>(SacrificeParser.key(stack.getHoverName().getString()), count, stack.copy()));
        }
        if (primed && changed) {
            for (SlotDiff.Removed<ItemStack> r : SlotDiff.removed(before, after)) {
                removals.add(value(r, now));
            }
        }
        if (changed || !primed) {
            before.clear();
            before.addAll(after);
        }
        primed = true;
    }

    private RemovalLog.Removal value(SlotDiff.Removed<ItemStack> r, long now) {
        ItemStack stack = r.stack();
        if (cfg().fullAppraisal) {
            ItemAppraisal.Appraisal a = ItemAppraisal.of(stack);
            return new RemovalLog.Removal(r.key(), a.unit() * r.count(), !a.priced() || !a.complete(), now);
        }
        Long base = ItemAppraisal.firstPrice(ItemPriceKey.keysFor(stack),
                cfg().sellSide ? ItemAppraisal.Side.SELL : ItemAppraisal.Side.BUY);
        return new RemovalLog.Removal(r.key(), base == null ? 0L : base * r.count(), base == null, now);
    }

    /** Claims the matching removal for the pending sacrifice, if one is there yet. */
    private void settle(long now) {
        if (pending == null || pending.cost != null) {
            return;
        }
        RemovalLog.Removal r = removals.take(SacrificeParser.key(pending.item), now);
        if (r != null) {
            pending.cost = r.value();
            pending.approx = r.approx();
        }
    }

    /**
     * Hands the pending sacrifice to both ledgers. With {@code fallback}, a cost still unknown is
     * the base item priced by name, marked approximate.
     */
    private void commitPending(boolean fallback) {
        Pending p = pending;
        if (p == null || (p.cost == null && !fallback)) {
            return;
        }
        pending = null;
        if (p.cost == null) {
            Long base = price(SacrificeParser.key(p.item));
            p.cost = base == null ? 0L : base;
            p.approx = true;
        }
        SacrificeLedger.Sacrifice s = new SacrificeLedger.Sacrifice(
                p.item, p.essence, p.cost, p.approx, List.copyOf(p.bonuses));
        Long essencePrice = price(ESSENCE_ID);
        ensureLoaded();
        session.add(s, essencePrice, SacrificeTracker::price);
        allTime.add(s, essencePrice, SacrificeTracker::price);
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Sacrifice] {} -> {} essence, {} bonus, cost {}{}",
                p.item, p.essence, p.bonuses.size(), p.approx ? "~" : "", p.cost);
        version++;
        save();
    }

    public void resetSession() {
        session = new SacrificeLedger();
        version++;
    }

    /** Clears this profile's all-time counts too. */
    public void resetAllTime() {
        ensureLoaded();
        allTime = new SacrificeLedger();
        session = new SacrificeLedger();
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
        SacrificeLedger read = new SacrificeLedger();
        try {
            Path path = file();
            if (Files.isRegularFile(path)) {
                try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    SacrificeLedger parsed = SBSFiles.GSON.fromJson(reader, SacrificeLedger.class);
                    if (parsed != null) {
                        read = parsed;
                    }
                }
            }
        } catch (Exception e) {
            // A corrupt file is logged, not fatal; it stays on disk and memory starts empty.
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Sacrifice] could not read {} ({})", FILE, e.toString());
        }
        allTime = read;
        session = new SacrificeLedger();
        pending = null;
        removals.clear();
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
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Sacrifice] could not write {} ({})", FILE, e.toString());
        }
    }
}
