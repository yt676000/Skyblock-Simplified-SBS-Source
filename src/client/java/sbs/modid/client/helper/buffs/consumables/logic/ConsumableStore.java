/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.buffs.consumables.logic;

import net.minecraft.world.item.ItemStack;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.alert.Alerts;
import sbs.modid.client.core.async.SbsExecutors;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.ProfileContext;
import sbs.modid.client.core.config.ProfileScopedStore;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.helper.buffs.BuffDuration;
import sbs.modid.client.helper.buffs.consumables.model.ConsumableKind;
import sbs.modid.client.helper.buffs.consumables.model.ConsumableTimer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Consumable Timers for the current account and SkyBlock profile, kept across restarts in
 * {@code consumables.json}. Wires {@link ConsumableBook} (every rule) to its inputs - chat, the tab
 * footer, the Active Effects menu, item use - and to the alert system and the disk.
 *
 * <p><b>Why this is allowed.</b> It displays and alerts, nothing more: it reads chat, the tab list,
 * a menu only once the player has opened it, and the lore of an item the player just used. It never
 * consumes, clicks, opens a menu or sends a command, so it plays nothing for the player.
 *
 * <p>Every hook runs on the client thread. The file write goes through {@link SbsExecutors#io()},
 * with the JSON built here first.
 */
public final class ConsumableStore implements ProfileScopedStore {

    private static final ConsumableStore INSTANCE = new ConsumableStore();
    private static final String FILE = "consumables.json";
    private static final long TICK_INTERVAL_MS = 1_000L;
    /** Saved at least this often while anything is tracked, so a crash costs at most a minute. */
    private static final long SAVE_INTERVAL_MS = 60_000L;
    /** How long a used item's lore duration waits for the "BUFF!" line it may belong to. */
    private static final long USE_WINDOW_MS = 5_000L;

    private record PendingUse(String name, BuffDuration.Parsed duration, long at) {
    }

    private ConsumableBook book = new ConsumableBook();
    private boolean loaded;
    private boolean dirty;
    private long lastTickAt;
    private long lastSaveAt;
    private PendingUse pendingUse;

    private ConsumableStore() {
        ProfileContext.getInstance().register(this);
    }

    public static ConsumableStore getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.BuffsSettings cfg() {
        return ConfigManager.getInstance().get().buffs;
    }

    private static boolean enabled() {
        SBSConfig.BuffsSettings cfg = cfg();
        return cfg.enabled && cfg.consumablesEnabled;
    }

    private static ConsumableBook.Context context() {
        return new ConsumableBook.Context(!SkyBlockLocation.zone().isEmpty(), SkyBlockLocation.inDungeon());
    }

    // ------------------------------------------------------------------ inputs

    /** One chat line, colour codes included. */
    public synchronized void onChat(String text) {
        if (!enabled()) {
            return;
        }
        ConsumableLines.Reading reading = ConsumableLines.parse(text);
        if (reading == null) {
            if (ConsumableLines.looksRelated(text)) {
                ConsumableCapture.getInstance().logOnce("unparsed chat", sbs.modid.client.core.util.PlainText.strip(text));
            }
            return;
        }
        ensureLoaded();
        long now = System.currentTimeMillis();
        switch (reading) {
            case ConsumableLines.Consumed c -> {
                book.consume(c, now);
                log("consumed {} ({}, {})", c.name(), c.kind(), c.durationMs() < 0 ? "no duration" : c.durationMs() + " ms");
            }
            case ConsumableLines.EffectGained g -> {
                // Inside a dungeon the effects are the run's own (Dungeon Potions) and last the run.
                if (SkyBlockLocation.inDungeon()) {
                    return;
                }
                PendingUse use = pendingUse;
                BuffDuration.Parsed estimate = use != null && now - use.at() <= USE_WINDOW_MS ? use.duration() : null;
                book.effectGained(g.name(), estimate == null ? ConsumableTimer.UNKNOWN : estimate.millis(),
                        estimate == null ? 0 : estimate.precisionMs(), now);
                log("effect gained {} (estimate {})", g.name(), estimate == null ? "none" : use.name());
            }
            case ConsumableLines.ExpiresIn e -> logCheck(book.serverRead(e.name(), ConsumableKind.ofName(e.name()),
                    e.remainingMs(), e.precisionMs(), ConsumableTimer.Source.CHAT, now));
            case ConsumableLines.Expired x -> fire(book.expired(x.name()));
        }
        dirty = true;
    }

    /** One tab footer reading, every two seconds from {@code BuffTracker}. */
    public synchronized void onFooter(EffectsFooter.Reading reading, long now) {
        if (!enabled()) {
            return;
        }
        ensureLoaded();
        if (reading.godPotionText() != null && reading.godPotion() == null
                && !reading.godPotionText().equals("0s")) {
            ConsumableCapture.getInstance().logOnce("unread footer time", reading.godPotionText());
        }
        if (reading.cookieText() != null && reading.cookie() == null) {
            ConsumableCapture.getInstance().logOnce("unread footer time", reading.cookieText());
        }
        List<ConsumableBook.ClockCheck> checks = new ArrayList<>();
        List<ConsumableBook.Event> events = book.applyFooter(reading, context(), now, checks);
        checks.forEach(this::logCheck);
        events.forEach(this::fire);
        dirty |= !events.isEmpty() || !checks.isEmpty();
    }

    /** The Active Effects menu the player opened, from {@link ConsumableCapture}. */
    public synchronized void onMenu(EffectsMenu.Reading reading) {
        if (!enabled() || !cfg().consumablesReadMenu || reading.effects().isEmpty()) {
            return;
        }
        ensureLoaded();
        List<ConsumableBook.ClockCheck> checks = new ArrayList<>();
        List<ConsumableBook.Event> events = book.applyMenu(reading, System.currentTimeMillis(), checks);
        checks.forEach(this::logCheck);
        events.forEach(this::fire);
        log("menu read: {} effects (complete={})", reading.effects().size(), reading.complete());
        dirty = true;
    }

    /** A right-click with {@code stack} in hand, from the item-use hook (client thread). */
    public synchronized void onItemUse(ItemStack stack) {
        ConsumableCapture.Use use = ConsumableCapture.getInstance().onItemUse(stack);
        if (use == null || !enabled()) {
            return;
        }
        BuffDuration.Parsed duration = ConsumableLines.loreDuration(use.timeLines());
        pendingUse = duration == null ? null : new PendingUse(use.name(), duration, System.currentTimeMillis());
    }

    /** Counts the timers down and fires due alerts. Throttles itself. */
    public void onClientTick() {
        long now = System.currentTimeMillis();
        if (now - lastTickAt < TICK_INTERVAL_MS) {
            return;
        }
        lastTickAt = now;
        if (!enabled()) {
            return;
        }
        synchronized (this) {
            ensureLoaded();
            book.advance(now, context(), ConsumableStore::policy).forEach(this::fire);
            if (dirty || (!book.timers.isEmpty() && now - lastSaveAt >= SAVE_INTERVAL_MS)) {
                save(now);
            }
        }
    }

    // ------------------------------------------------------------------ queries

    /** The card's rows: soonest end first, unknown last, each with whether it is paused. */
    public synchronized List<Row> rows() {
        if (!enabled()) {
            return List.of();
        }
        ensureLoaded();
        ConsumableBook.Context ctx = context();
        List<Row> rows = new ArrayList<>();
        for (ConsumableTimer t : book.sorted()) {
            boolean estimated = t.confidence == ConsumableTimer.Confidence.ESTIMATED
                    || t.restoredUnconfirmed || t.precisionMs > 60_000L;
            rows.add(new Row(t.label(), t.remainingMs, book.paused(t, ctx), estimated));
        }
        return rows;
    }

    /** One card row; {@code remainingMs} is negative when unknown. */
    public record Row(String label, long remainingMs, boolean paused, boolean estimated) {
    }

    // ------------------------------------------------------------------ alerts

    static ConsumableBook.Policy policy(ConsumableKind kind) {
        SBSConfig.BuffsSettings c = cfg();
        return switch (kind) {
            case GOD_POTION -> new ConsumableBook.Policy(c.consumableAlertGodPotion,
                    c.consumableWarnGodPotionMinutes * 60_000L, c.consumableEndAlert);
            case BOOSTER_COOKIE -> new ConsumableBook.Policy(c.consumableAlertCookie,
                    c.consumableWarnCookieHours * 3_600_000L, c.consumableEndAlert);
            case POTION -> new ConsumableBook.Policy(c.consumableAlertPotion,
                    c.consumableWarnPotionMinutes * 60_000L, c.consumableEndAlert);
            case MIXIN -> new ConsumableBook.Policy(c.consumableAlertMixin,
                    c.consumableWarnMixinMinutes * 60_000L, c.consumableEndAlert);
            case OTHER -> new ConsumableBook.Policy(c.consumableAlertOther,
                    c.consumableWarnOtherMinutes * 60_000L, c.consumableEndAlert);
        };
    }

    private void fire(ConsumableBook.Event event) {
        if (event == null) {
            return;
        }
        int channels = cfg().consumableAlertChannels;
        switch (event) {
            case ConsumableBook.Warning w -> {
                log("warning {} at {} ms", w.label(), w.remainingMs());
                Alerts.send(Alerts.Alert.of(w.label() + " running out",
                        "Ends in " + DurationFormat.remaining(w.remainingMs())), channels);
            }
            case ConsumableBook.Ended e -> {
                ConsumableBook.Policy p = policy(e.kind());
                log("ended {} (estimated={}, alert={})", e.label(), e.estimated(), p.enabled() && p.endAlert());
                if (p.enabled() && p.endAlert()) {
                    Alerts.send(Alerts.Alert.of(e.label() + " has run out",
                            e.estimated() ? "By SBS's count - Hypixel has not said so yet" : ""), channels);
                }
            }
        }
        dirty = true;
    }

    private void logCheck(ConsumableBook.ClockCheck check) {
        if (check == null) {
            return;
        }
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Consumables] clock check {}: server={} ms, online-only={} ms, "
                        + "real-time={} ms -> {}{}", check.key(), check.serverMs(), check.onlineMs(), check.realMs(),
                check.matched() == null ? "neither" : check.matched(), check.switched() ? " (clock switched)" : "");
        dirty = true;
    }

    private static void log(String format, Object... args) {
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Consumables] " + format, args);
    }

    // ------------------------------------------------------------------ persistence

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
        ConsumableBook read = new ConsumableBook();
        try {
            Path path = file();
            if (Files.isRegularFile(path)) {
                try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    ConsumableBook parsed = SBSFiles.GSON.fromJson(reader, ConsumableBook.class);
                    if (parsed != null && parsed.timers != null) {
                        if (parsed.learnedClocks == null) {
                            parsed.learnedClocks = new java.util.LinkedHashMap<>();
                        }
                        parsed.timers.values().removeIf(t -> t == null || t.key == null || t.kind == null
                                || t.clock == null);
                        parsed.timers.values().forEach(t -> {
                            if (t.firedThresholds == null) {
                                t.firedThresholds = new ArrayList<>();
                            }
                        });
                        read = parsed;
                    }
                }
            }
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Consumables] could not read {} ({})", FILE, e.toString());
        }
        read.restore(System.currentTimeMillis());
        book = read;
        loaded = true;
        dirty = false;
        pendingUse = null;
    }

    @Override
    public synchronized void flushProfile() {
        if (loaded) {
            save(System.currentTimeMillis());
        }
    }

    private void save(long now) {
        dirty = false;
        lastSaveAt = now;
        // "default" is the placeholder before the profile context knows where it is.
        if (ProfileContext.getInstance().profile().equals("default")) {
            return;
        }
        Path path = file();
        String json = SBSFiles.GSON.toJson(book);
        SbsExecutors.io().execute(() -> {
            try {
                Files.createDirectories(path.getParent());
                Files.writeString(path, json, StandardCharsets.UTF_8);
            } catch (Exception e) {
                SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Consumables] could not write {}", FILE, e);
            }
        });
    }
}
