/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.bazaar.logic;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.ProfileContext;
import sbs.modid.client.core.config.ProfileScopedStore;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.economy.bazaar.model.CancelledOrder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The <b>Bazaar order history</b>: unfilled remainders of buy orders the player cancelled, kept so
 * each one can be re-placed in a click instead of being recalculated from memory.
 *
 * <p>Written only by {@link BazaarOrderTracker}, and only once a cancellation has been
 * <i>confirmed</i> by Hypixel's own refund line - never on the click that requests it. A cancel that
 * the player backs out of, or that the server never processes because they disconnected first,
 * therefore leaves nothing behind, which is the entire reason the confirmation gates the write.
 *
 * <p>Profile-scoped, like {@link BazaarOrderTracker}'s own cache: orders belong to one account and
 * one SkyBlock profile, and a remainder shown against the wrong profile is a remainder for coins the
 * player does not have there. Persisted to {@code bazaar_order_history.json} so it survives a relog -
 * a cancelled order is most often re-placed in the next session, not the current one.
 *
 * <p><b>Entries expire.</b> A remainder is a note about a market position, and a week-old note about
 * a price that has moved is worse than no note: it invites re-placing an order at a figure that is
 * no longer competitive. {@code orderHistoryExpiryHours} bounds how long one is offered (0 disables
 * the expiry for players who would rather sweep the list themselves).
 */
public final class BazaarOrderHistory implements ProfileScopedStore {

    private static final BazaarOrderHistory INSTANCE = new BazaarOrderHistory();

    private static final String FILE_NAME = "bazaar_order_history.json";

    /**
     * Hard cap on stored rows, independent of the expiry. The panel shows a handful; the rest is a
     * file that grows for as long as somebody flips, and an unbounded one eventually costs a visible
     * hitch on the profile switch that reads it.
     */
    private static final int MAX_ENTRIES = 64;

    /** Immutable snapshot, safe to read from the render thread while chat writes a new one. */
    private volatile List<CancelledOrder> entries = List.of();

    private boolean loaded;

    private BazaarOrderHistory() {
        ProfileContext.getInstance().register(this);
    }

    public static BazaarOrderHistory getInstance() {
        return INSTANCE;
    }

    /** Master toggle for recording and for the panel alike. */
    public static boolean enabled() {
        return ConfigManager.getInstance().get().bazaar.orderHistory;
    }

    // ------------------------------------------------------------------ persistence

    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        entries = read();
    }

    private static List<CancelledOrder> read() {
        Path path = SBSFiles.profileFile(FILE_NAME);
        try {
            if (!Files.exists(path)) {
                return List.of();
            }
            java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<List<CancelledOrder>>() {
            }.getType();
            try (var reader = Files.newBufferedReader(path)) {
                List<CancelledOrder> list = SBSFiles.GSON.fromJson(reader, type);
                if (list == null) {
                    return List.of();
                }
                List<CancelledOrder> kept = new ArrayList<>(list.size());
                for (CancelledOrder entry : list) {
                    if (entry == null || entry.remaining() <= 0) {
                        continue;
                    }
                    // Gson writes fields directly, so the constructor's tick snap is bypassed here -
                    // re-apply it, or an off-grid price written by an older build survives and its
                    // key() hashes apart from the same order read fresh.
                    entry.snapPriceToTick();
                    kept.add(entry);
                }
                return List.copyOf(kept);
            }
        } catch (Throwable t) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][Bazaar] Failed to load the order history", t);
            return List.of();
        }
    }

    private void persist(List<CancelledOrder> snapshot) {
        try {
            Path path = SBSFiles.profileFile(FILE_NAME);
            Files.createDirectories(path.getParent());
            try (var writer = Files.newBufferedWriter(path)) {
                SBSFiles.GSON.toJson(snapshot, writer);
            }
        } catch (Throwable t) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][Bazaar] Failed to save the order history", t);
        }
    }

    @Override
    public void flushProfile() {
        persist(entries);
    }

    @Override
    public void reloadProfile() {
        loaded = false;
        entries = List.of();
        ensureLoaded();
    }

    // ------------------------------------------------------------------ reading

    /**
     * The live history, newest first, with expired rows already dropped.
     *
     * <p>Expiry is applied on read rather than on a timer: nothing here is worth waking a scheduler
     * for, and a row that ages out while the panel is open should stop being offered at the next
     * frame, not at the next tick of some sweep.
     */
    public List<CancelledOrder> entries() {
        ensureLoaded();
        List<CancelledOrder> current = entries;
        long cutoff = expiryCutoff();
        List<CancelledOrder> live = new ArrayList<>(current.size());
        for (CancelledOrder entry : current) {
            if (cutoff <= 0 || entry.cancelledAt() >= cutoff) {
                live.add(entry);
            }
        }
        if (live.size() != current.size()) {
            // Expired rows leave the stored list too, so the file does not keep them for a profile
            // that is rarely opened.
            entries = List.copyOf(live);
            persist(entries);
        }
        live.sort(Comparator.comparingLong(CancelledOrder::cancelledAt).reversed());
        return List.copyOf(live);
    }

    /** Epoch millis before which an entry is stale, or 0 when the expiry is switched off. */
    private static long expiryCutoff() {
        int hours = ConfigManager.getInstance().get().bazaar.orderHistoryExpiryHours;
        if (hours <= 0) {
            return 0;
        }
        return System.currentTimeMillis() - hours * 3_600_000L;
    }

    // ------------------------------------------------------------------ writing

    /**
     * Records a confirmed cancellation's remainder, replacing any earlier row for the same
     * item + price.
     *
     * <p>Replacing rather than summing is deliberate. Two cancellations at the same price are two
     * readings of the same intention, the later one taken after the earlier remainder was already
     * re-placed and partly filled again; adding them would offer an amount larger than anything the
     * player ever ordered. A fully filled order (remainder 0) records nothing at all - there is
     * nothing left to re-place, and a zero row would be a standing invitation to place an empty
     * order.
     */
    public void record(CancelledOrder order) {
        if (!enabled() || order == null || order.remaining() <= 0) {
            return;
        }
        ensureLoaded();
        List<CancelledOrder> next = new ArrayList<>(entries.size() + 1);
        for (CancelledOrder existing : entries) {
            if (!existing.key().equals(order.key())) {
                next.add(existing);
            }
        }
        next.add(order);
        next.sort(Comparator.comparingLong(CancelledOrder::cancelledAt).reversed());
        while (next.size() > MAX_ENTRIES) {
            next.remove(next.size() - 1);
        }
        entries = List.copyOf(next);
        persist(entries);
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Bazaar] Order history: remembered {} x{} still owed at {} (of {} ordered)",
                order.itemName(), order.remaining(), order.price(), order.ordered());
    }

    /** Drops one row - the player re-ordered it, or dismissed it from the panel. */
    public void forget(String key) {
        ensureLoaded();
        List<CancelledOrder> next = new ArrayList<>(entries.size());
        boolean removed = false;
        for (CancelledOrder existing : entries) {
            if (existing.key().equals(key)) {
                removed = true;
                continue;
            }
            next.add(existing);
        }
        if (!removed) {
            return;
        }
        entries = List.copyOf(next);
        persist(entries);
    }

    /**
     * Reduces one row by what was just re-ordered, dropping it when nothing is left owed.
     *
     * <p>Addressed by {@link CancelledOrder#key()} rather than re-derived from the placed order,
     * because the two need not agree on price: re-ordering deliberately leaves the price to the
     * player, so the new order's price is usually <i>not</i> the cancelled one's, and a lookup by
     * item + price would miss the very row it was sent to clear. {@link BazaarReorder} carries the
     * key of the row the flow started from, which is the only thing that identifies it across that
     * change.
     *
     * <p>Reducing rather than deleting matters when the player re-orders less than the whole
     * remainder: the rest stays on offer instead of being silently forgotten.
     */
    public void reduceRow(String key, int placed) {
        if (key == null || key.isEmpty() || placed <= 0) {
            return;
        }
        ensureLoaded();
        List<CancelledOrder> next = new ArrayList<>(entries.size());
        boolean changed = false;
        for (CancelledOrder existing : entries) {
            if (!existing.key().equals(key)) {
                next.add(existing);
                continue;
            }
            changed = true;
            int left = existing.remaining() - placed;
            if (left > 0) {
                // The ordered baseline shrinks with the remainder; the filled count does NOT. What
                // was just re-placed is not something that filled, and folding it into `filled`
                // would make the tooltip claim units arrived that the player has never received.
                // Shrinking the baseline instead keeps ordered - filled == remaining, so the row
                // stays internally consistent and the bar keeps meaning "of what I am still
                // tracking, this much is already in hand".
                next.add(new CancelledOrder(existing.itemId(), existing.itemName(), left,
                        existing.filled() + left, existing.filled(), existing.price(),
                        existing.cancelledAt()));
            }
        }
        if (!changed) {
            return;
        }
        entries = List.copyOf(next);
        persist(entries);
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Bazaar] Order history: re-ordered x{} against {}",
                placed, key);
    }

    /** Empties the history for the current profile. */
    public void clear() {
        ensureLoaded();
        entries = List.of();
        persist(entries);
    }
}
