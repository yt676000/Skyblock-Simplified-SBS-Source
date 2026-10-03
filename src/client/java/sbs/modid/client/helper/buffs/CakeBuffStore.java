/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.buffs;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.alert.Alerts;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.ProfileContext;
import sbs.modid.client.core.config.ProfileScopedStore;
import sbs.modid.client.core.config.SBSFiles;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The Century Cake buffs of the current account and SkyBlock profile, kept across restarts in
 * {@code cake_buffs.json}. Fed from the chat funnel ({@link #onChat}); the rules are in
 * {@link CakeBuffBook}, the wording in {@link CakeBuffParser}.
 */
public final class CakeBuffStore implements ProfileScopedStore {

    private static final CakeBuffStore INSTANCE = new CakeBuffStore();
    private static final String FILE = "cake_buffs.json";
    private static final long TICK_INTERVAL_MS = 5_000L;

    private volatile CakeBuffBook book = new CakeBuffBook();
    private volatile boolean loaded;
    private long lastTickAt;

    private CakeBuffStore() {
        ProfileContext.getInstance().register(this);
    }

    public static CakeBuffStore getInstance() {
        return INSTANCE;
    }

    /** The active cake buffs, soonest to expire first. */
    public synchronized List<CakeBuffBook.Buff> active() {
        ensureLoaded();
        return book.active(System.currentTimeMillis());
    }

    /** One chat line. Books an eat or refresh; logs a cake-shaped line that did not parse. */
    public synchronized void onChat(String line) {
        if (!CakeBuffParser.looksLikeCake(line)) {
            return;
        }
        CakeBuffParser.Eat eat = CakeBuffParser.parse(line);
        if (eat == null) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Cake] a \"Yum!\" line that did not parse - the "
                    + "wording may have changed: \"{}\"", line.replaceAll("(?i)§.", ""));
            return;
        }
        ensureLoaded();
        book.apply(eat, System.currentTimeMillis());
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Cake] {} +{} {} for {}h", eat.refresh() ? "refreshed" : "ate",
                eat.amount(), eat.stat(), eat.hours());
        save();
    }

    /** Drops expired buffs and fires the "first cake runs out soon" alert. Throttles itself. */
    public void onClientTick() {
        long now = System.currentTimeMillis();
        if (now - lastTickAt < TICK_INTERVAL_MS) {
            return;
        }
        lastTickAt = now;
        synchronized (this) {
            ensureLoaded();
            boolean changed = book.prune(now);
            var cfg = ConfigManager.getInstance().get().buffs;
            long next = book.nextExpiry(now);
            if (cfg.enabled && cfg.cakeWarnHours > 0 && next > 0 && next != book.warnedForExpiry
                    && next - now <= cfg.cakeWarnHours * 3_600_000L) {
                book.warnedForExpiry = next;
                changed = true;
                CakeBuffBook.Buff first = book.active(now).get(0);
                Alerts.send(Alerts.Alert.of("Cake buff running out",
                        "+" + first.amount + " " + first.stat + " ends in "
                                + CakeBuffBook.remaining(next, now)), cfg.cakeWarnChannels);
            }
            if (changed) {
                save();
            }
        }
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
        CakeBuffBook read = new CakeBuffBook();
        try {
            Path path = file();
            if (Files.isRegularFile(path)) {
                try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    CakeBuffBook parsed = SBSFiles.GSON.fromJson(reader, CakeBuffBook.class);
                    if (parsed != null && parsed.buffs != null) {
                        read = parsed;
                    }
                }
            }
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Cake] could not read {} ({})", FILE, e.toString());
        }
        book = read;
        loaded = true;
    }

    @Override
    public void flushProfile() {
        if (loaded) {
            save();
        }
    }

    private synchronized void save() {
        // "default" is the placeholder before the profile context knows where it is.
        if (ProfileContext.getInstance().profile().equals("default")) {
            return;
        }
        try {
            Path path = file();
            Files.createDirectories(path.getParent());
            Files.writeString(path, SBSFiles.GSON.toJson(book), StandardCharsets.UTF_8);
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Cake] could not write {}", FILE, e);
        }
    }
}
