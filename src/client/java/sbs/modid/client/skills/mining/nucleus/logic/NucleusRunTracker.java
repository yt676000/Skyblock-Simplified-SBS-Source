/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.nucleus.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.alert.Alerts;
import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.core.async.SbsExecutors;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.ProfileContext;
import sbs.modid.client.core.config.ProfileScopedStore;
import sbs.modid.client.core.config.SBSConfig.NucleusRunSettings;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.core.config.SaveThrottle;
import sbs.modid.client.core.item.SkyblockItem;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.util.NumberDisplay;
import sbs.modid.client.economy.itemvalue.ItemAppraisal;
import sbs.modid.client.economy.prices.BazaarPriceCache;
import sbs.modid.client.economy.prices.LbinCache;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemCatalog;
import sbs.modid.client.helper.map.logic.HollowsTracker;
import sbs.modid.client.skills.mining.logic.GemstoneCatalog;
import sbs.modid.client.skills.mining.nucleus.model.NucleusCostRules;
import sbs.modid.client.skills.mining.nucleus.model.NucleusRunData.Run;
import sbs.modid.client.skills.mining.nucleus.model.NucleusSignals;
import sbs.modid.client.social.chat.logic.SBSChat;
import sbs.modid.client.ui.hud.logic.PetTracker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The Nucleus Run Profit Tracker's connection to the game: chat and ticks in, the player's inventory
 * read, the profile's {@code nucleus_runs.json} written, the capture log kept, the chat summary sent.
 * The bookkeeping is {@link NucleusRunSession} and the pure classes behind it.
 *
 * <p>Read-only by construction: it reads chat, the player's own inventory and an open HotM menu, and
 * draws. It never opens a menu, clicks, claims or sends anything to the server.
 *
 * <p>Everything earned is counted on the Crystal Hollows only. Leaving the island does not end the
 * run - it pauses its clock - and a world change clears only what belongs to one lobby: an open reward
 * block, a cost line waiting for its decrease, the inventory baseline.
 */
public final class NucleusRunTracker implements ProfileScopedStore {

    private static final String FILE = "nucleus_runs.json";
    /** The inventory is re-read this often on the Hollows. */
    private static final int INVENTORY_EVERY_TICKS = 4;
    private static final long PREVIEW_EVERY_MS = 1_000L;
    /** Online time is saved at least this often while it accrues. */
    private static final long CLOCK_SAVE_MS = 30_000L;
    private static final long ARRIVAL_PRUNE_MS = 10_000L;

    private static NucleusRunTracker instance;

    private final NucleusRunLedger ledger = new NucleusRunLedger();
    private final NucleusItemIds itemIds = new NucleusItemIds(name -> {
        SkyBlockItemCatalog.Entry entry = SkyBlockItemCatalog.getInstance().byName(name);
        return entry == null ? null : entry.id;
    });
    private final NucleusRunSession session =
            new NucleusRunSession(ledger, itemIds::resolve, NucleusRunTracker::log);
    private final SaveThrottle throttle = new SaveThrottle();
    private final MoleReminder mole = new MoleReminder();

    private long lastClockSaveAt;
    private long lastArrivalPruneAt;
    private Map<String, Long> inventory;
    private int tickCounter;
    private String lastChatLine = "";
    private long bundleCaptureUntil = -1L;
    private String lastHotmLore;
    private String lastZone = "";

    private NucleusPricing.Summary preview;
    private long previewAt;

    private NucleusRunTracker() {
        ProfileContext.getInstance().register(this);
    }

    public static synchronized NucleusRunTracker getInstance() {
        if (instance == null) {
            instance = new NucleusRunTracker();
        }
        return instance;
    }

    private static NucleusRunSettings cfg() {
        return ConfigManager.getInstance().get().nucleusRun;
    }

    private static boolean onHollows() {
        return SkyBlockLocation.onIsland(HollowsTracker.ISLAND);
    }

    private static NucleusRunSession.Valuation valuation() {
        NucleusRunSettings settings = cfg();
        return new NucleusRunSession.Valuation(NucleusRunTracker::livePrice,
                settings.priceSide == 1 ? NucleusPricing.Side.SELL_OFFER : NucleusPricing.Side.INSTANT_SELL,
                settings.countSelfObtainedCosts);
    }

    // ------------------------------------------------------------------ read-out for the card

    public NucleusRunLedger ledger() {
        return ledger;
    }

    /** The open run at today's prices, refreshed once a second; {@code null} before there is one. */
    public NucleusPricing.Summary preview() {
        return preview;
    }

    // ------------------------------------------------------------------ chat

    /** Every chat line, with its component - the {@code [Sacks]} breakdown is in the hover. */
    public void onChat(String raw, Component component) {
        if (raw == null || !cfg().enabled || !onHollows()) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            String plain = NucleusChatParser.strip(raw);
            if (plain.startsWith("[SBS]")) {
                return;     // this tracker's own summary comes back through the same hook
            }
            String hover = component == null ? "" : hoverText(component);
            capture(raw, hover, plain, now);
            if (!NucleusSignals.SACKS.matcher(plain).find()) {
                lastChatLine = plain;
            }
            if (NucleusSignals.BUNDLE_HEADER.matcher(plain).matches()) {
                bundleCaptureUntil = now + NucleusSignals.BUNDLE_CAPTURE_MS;
            }
            ledger.ensureRun(now);
            handle(session.onChat(raw, hover, now, valuation()), now);
        } catch (RuntimeException e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Nucleus] chat line not processed: {}", raw, e);
        }
    }

    private void handle(NucleusRunSession.Result result, long now) {
        for (NucleusRunLedger.Finished finished : result.finished()) {
            preview = null;
            dirty(true, now);
            Run run = finished.run();
            log("run finished: nucleus profit=" + run.nucleusProfit + " run profit=" + run.runProfit
                    + " online=" + run.onlineMs / 1000 + "s partial=" + run.partial
                    + " unpriced=" + finished.summary().unpriced());
            if (cfg().chatSummary) {
                sendSummary(finished);
            }
        }
        if (result.changed()) {
            dirty(false, now);
        }
        remind(mole.onEvents(result.events(), ledger, pet(), cfg().moleChance));
    }

    // ------------------------------------------------------------------ Mole reminder

    private static MoleReminder.Pet pet() {
        PetTracker pets = PetTracker.getInstance();
        return MoleReminder.Pet.of(pets.hasPet(), pets.name(), pets.level());
    }

    /** Arriving in the Crystal Nucleus zone is the reminder's early trigger. */
    private void checkZone() {
        String zone = SkyBlockLocation.zone();
        if (zone.equals(lastZone)) {
            return;
        }
        lastZone = zone;
        remind(mole.onZone(zone, ledger, pet(), cfg().moleChance));
    }

    /** The probe lines always reach the capture log; the reminders only while the toggle is on. */
    private static void remind(MoleReminder.Output output) {
        output.log().forEach(NucleusRunTracker::log);
        if (!cfg().moleReminder) {
            return;
        }
        int channels = cfg().moleReminderChannels;
        for (MoleReminder.Reminder reminder : output.reminders()) {
            log("mole reminder: " + reminder.title() + " - " + reminder.detail());
            Alerts.send(Alerts.Alert.of(reminder.title(), reminder.detail()), channels);
        }
    }

    // ------------------------------------------------------------------ tick

    /** Every client tick. */
    public void onClientTick() {
        if (!cfg().enabled) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean hollows = onHollows();
        if (ledger.tick(now, hollows)) {
            dirty(false, now);
        }
        if (hollows) {
            Screen screen = GuiStateManager.getInstance().getCurrentScreen();
            boolean container = screen instanceof AbstractContainerScreen<?>;
            if (container) {
                session.containerOpen(now);
            }
            handle(session.poll(now, valuation()), now);
            if (++tickCounter % INVENTORY_EVERY_TICKS == 0) {
                sampleInventory(container, now);
                checkZone();
            }
            if (now - lastClockSaveAt >= CLOCK_SAVE_MS && ledger.current() != null) {
                lastClockSaveAt = now;
                dirty(false, now);
            }
        }
        if (now - lastArrivalPruneAt >= ARRIVAL_PRUNE_MS) {
            lastArrivalPruneAt = now;
            ledger.pruneArrivals(now);
        }
        if (ledger.current() != null && now - previewAt >= PREVIEW_EVERY_MS) {
            previewAt = now;
            NucleusRunSession.Valuation v = valuation();
            preview = NucleusPricing.preview(ledger.current(), v.book(), v.side(), v.selfObtainedCounted());
        }
        if (throttle.pending(now)) {
            save(now);
        }
    }

    /** Lobby change, island change, disconnect. */
    public void onWorldChange() {
        session.clear();
        mole.onWorldChange();
        lastZone = "";
        inventory = null;
        ledger.pauseClock();
        bundleCaptureUntil = -1L;
        if (throttle.dirty()) {
            save(System.currentTimeMillis());
        }
    }

    /**
     * Decreases feed the cost window. With a container open the baseline is only re-read: a decrease
     * in a menu is a sale, a trade or a storage move, never a cost.
     */
    private void sampleInventory(boolean containerOpen, long now) {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            inventory = null;
            return;
        }
        Map<String, Long> counts = countInventory(player);
        Map<String, Long> before = inventory;
        inventory = counts;
        if (before == null) {
            return;
        }
        boolean bundleWindow = now <= bundleCaptureUntil;
        Set<String> ids = new HashSet<>(before.keySet());
        ids.addAll(counts.keySet());
        for (String id : ids) {
            long delta = counts.getOrDefault(id, 0L) - before.getOrDefault(id, 0L);
            if (delta == 0) {
                continue;
            }
            if (bundleWindow) {
                log("bundle window: inventory " + id + " " + (delta > 0 ? "+" : "") + delta);
            }
            if (delta < 0 && !containerOpen) {
                if (session.costWaiting() || isCostCandidate(id)) {
                    log("cost: inventory " + id + " " + delta + " after \"" + lastChatLine + "\"");
                }
                handle(session.onDecrease(id, -delta, now), now);
            }
        }
    }

    private static boolean isCostCandidate(String id) {
        for (NucleusCostRules.Rule rule : NucleusCostRules.RULES) {
            if (id.equals(rule.fixedItemId()) || rule.candidates().contains(id)) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Long> countInventory(Player player) {
        Map<String, Long> counts = new HashMap<>();
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            String id = SkyblockItem.id(stack);
            if (id != null) {
                counts.merge(id, (long) stack.getCount(), Long::sum);
            }
        }
        return counts;
    }

    // ------------------------------------------------------------------ HotM capture

    /**
     * The {@code Crystal Hollows Crystals} slot of the open HotM menu, handed over by
     * {@code HotmTreeReader}. Only logged: its lore has not been captured yet, so nothing reads it.
     */
    public void onHotmCrystals(List<String> lore) {
        if (!cfg().enabled || lore == null) {
            return;
        }
        String joined = String.join(" | ", lore);
        if (!joined.equals(lastHotmLore)) {
            lastHotmLore = joined;
            log("hotm: Crystal Hollows Crystals lore = [" + joined + "]");
        }
    }

    // ------------------------------------------------------------------ pricing

    /**
     * Bazaar on the chosen side first, then any market by id, then lowest BIN by the display name (the
     * BIN index is keyed by normalised auction name, not by id). The name lookup is skipped for
     * gemstones: normalising strips their grade.
     */
    static Long livePrice(String id, String name, NucleusPricing.Side side) {
        if (id != null) {
            BazaarPriceCache.BzPrice bazaar = BazaarPriceCache.getInstance().priceOf(id);
            if (bazaar != null) {
                long value = side == NucleusPricing.Side.SELL_OFFER ? bazaar.buy() : bazaar.sell();
                if (value > 0) {
                    return value;
                }
            }
            Long any = ItemAppraisal.price(id, side == NucleusPricing.Side.SELL_OFFER
                    ? ItemAppraisal.Side.BUY : ItemAppraisal.Side.SELL);
            if (any != null && any > 0) {
                return any;
            }
            if (GemstoneCatalog.byItemId(id) != null) {
                return null;
            }
        }
        if (name != null) {
            String key = SkyblockItem.normalizeName(name);
            if (!key.isEmpty()) {
                Long lbin = LbinCache.getInstance().getLbin(key);
                if (lbin != null && lbin > 0) {
                    return lbin;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ chat summary

    private void sendSummary(NucleusRunLedger.Finished finished) {
        Run run = finished.run();
        NucleusPricing.Summary summary = finished.summary();
        StringBuilder top = new StringBuilder();
        for (NucleusPricing.Drop drop : summary.topDrops()) {
            if (!top.isEmpty()) {
                top.append("§7, ");
            }
            top.append("§f").append(drop.name()).append(drop.qty() > 1 ? " x" + drop.qty() : "")
                    .append(" §6").append(NumberDisplay.format(drop.value()));
        }
        SBSChat.send(Component.literal(" §dNucleus run§7 in " + duration(run.onlineMs)
                + (run.partial ? " §8(partly tracked)" : "")
                + "§7: Nucleus " + coins(run.nucleusProfit) + "§7, run total " + coins(run.runProfit)));
        if (!top.isEmpty()) {
            SBSChat.send(Component.literal(" §7Top drops: " + top));
        }
        if (!summary.unpriced().isEmpty()) {
            SBSChat.send(Component.literal(" §eUnpriced (counted 0): §7" + String.join(", ", summary.unpriced())));
        }
    }

    private static String coins(long value) {
        return (value < 0 ? "§c" : "§a+") + NumberDisplay.format(value);
    }

    /** {@code 12m 05s} / {@code 1h 03m}. */
    public static String duration(long ms) {
        long seconds = Math.max(0L, ms / 1000L);
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long rest = seconds % 60;
        return hours > 0
                ? String.format(Locale.ROOT, "%dh %02dm", hours, minutes)
                : String.format(Locale.ROOT, "%dm %02ds", minutes, rest);
    }

    // ------------------------------------------------------------------ reset

    /** History, totals and the open run of this profile. Called after the player confirmed. */
    public void resetAll() {
        ledger.reset();
        session.clear();
        session.forgetLogged();
        mole.reset();
        preview = null;
        log("history and totals reset for profile " + ProfileContext.getInstance().profile());
        save(System.currentTimeMillis());
    }

    // ------------------------------------------------------------------ persistence

    private void dirty(boolean oneShot, long now) {
        if (throttle.request(oneShot, now)) {
            save(now);
        }
    }

    /** JSON built here on the client thread, written on the ordered IO thread. */
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
                SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Nucleus] could not write {}", path, e);
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
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Nucleus] {} unreadable, starting empty", path, e);
        }
        String problem = ledger.load(json);
        if (problem != null) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Nucleus] {} not used: {}", path, problem);
        }
        preview = null;
        session.clear();
        mole.reset();
        inventory = null;
    }

    // ------------------------------------------------------------------ capture log

    private void capture(String raw, String hover, String plain, long now) {
        if (!cfg().captureLog) {
            return;
        }
        boolean bundleWindow = now <= bundleCaptureUntil;
        if (!bundleWindow && !NucleusSignals.CAPTURE.matcher(plain).find()) {
            return;
        }
        String where = bundleWindow ? " (bundle window)" : "";
        if (hover.isEmpty()) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Nucleus] chat{}: {}", where, raw);
        } else {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Nucleus] chat{}: {} || hover: {}", where, raw,
                    hover.replace('\n', '|'));
        }
    }

    private static void log(String message) {
        if (cfg().captureLog) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Nucleus] {}", message);
        }
    }

    private static String hoverText(Component component) {
        StringBuilder out = new StringBuilder();
        collectHover(component, out);
        return out.toString();
    }

    private static void collectHover(Component component, StringBuilder out) {
        if (component.getStyle().getHoverEvent() instanceof HoverEvent.ShowText(Component text)) {
            out.append(text.getString()).append('\n');
        }
        for (Component sibling : component.getSiblings()) {
            collectHover(sibling, out);
        }
    }
}
