/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.farming.logic;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.alert.AlertChannels;
import sbs.modid.client.core.alert.Alerts;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.ProfileContext;
import sbs.modid.client.core.config.ProfileScopedStore;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.core.data.VersionedDataStore;
import sbs.modid.client.core.tracker.TrackerStore;
import sbs.modid.client.core.util.RareDropLine;
import sbs.modid.client.economy.itemvalue.ItemAppraisal;
import sbs.modid.client.skills.SkillIslands;
import sbs.modid.client.skills.farming.model.CropType;
import sbs.modid.client.skills.farming.model.FarmDropData;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Rare drops while farming: counted per session and per profile, priced from the warm caches, and
 * announced per tier.
 *
 * <p><b>What counts as farming.</b> On a farming island ({@link SkillIslands#FARMING_ISLANDS})
 * with a farming tool in hand. A "RARE DROP!" line then counts if its item is in the farming-drop
 * list ({@code farming/drops.json}). Any other drop in that state is logged under
 * {@code [SBS][FarmDrops]} and NOT counted: that log is how the list grows from real data instead of
 * from a guess.
 *
 * <p><b>Unverified:</b> no farming drop line has ever been logged on the play instance. That crop
 * drops use the "RARE DROP!" family is an assumption. Any line on a farming island that says
 * "DROP!" or "CROP!" but does not parse is logged verbatim, so a different wording shows up.
 */
public final class FarmDropTracker implements ProfileScopedStore {

    private static final FarmDropTracker INSTANCE = new FarmDropTracker();

    private static final String RESOURCE =
            "/assets/" + SkyblockSimplifiedSBS.MOD_ID + "/farming/drops.json";
    private static final String FILE = "farm_drops.json";

    private static final VersionedDataStore<FarmDropData> DATA = new VersionedDataStore<>(
            "FarmDrops", RESOURCE, SBSFiles.root().resolve("data").resolve("farm-drops.json"),
            null, FarmDropData.class, 1);

    private final Map<String, Integer> session = new LinkedHashMap<>();
    private Map<String, Integer> totals = new LinkedHashMap<>();
    private boolean loaded;
    private long sessionStartedAt;

    private FarmDropTracker() {
        ProfileContext.getInstance().register(this);
    }

    public static FarmDropTracker getInstance() {
        return INSTANCE;
    }

    /** Loads the bundled drop list. Call on client init. */
    public static void load() {
        DATA.load();
        FarmDropData d = DATA.get();
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][FarmDrops] {} farming drop(s), data v{} ({})",
                d == null ? 0 : d.drops.size(), d == null ? 0 : d.dataVersion(), DATA.source());
    }

    private static SBSConfig.FarmingSettings cfg() {
        return ConfigManager.getInstance().get().farming;
    }

    // ------------------------------------------------------------------ chat

    /** Fed every colour-stripped chat line. */
    public void onChat(String plain) {
        if (!cfg().farmDrops || plain == null || !SkillIslands.onFarmingIsland()
                || CropType.forHeldTool() == null) {
            return;
        }
        RareDropLine.Drop drop = RareDropLine.parse(plain);
        if (drop == null) {
            String upper = plain.toUpperCase(Locale.ROOT);
            if (upper.contains("DROP!") || upper.contains("CROP!")) {
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][FarmDrops] unparsed drop-like line while "
                        + "farming: '{}'", plain);
            }
            return;
        }
        FarmDropData list = DATA.get();
        if (list == null || !list.isFarmingDrop(drop)) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][FarmDrops] {} drop '{}' while farming is not "
                    + "in the farming list - logged, not counted", drop.tier(), drop.item());
            return;
        }
        FarmDropData.Entry entry = list.byName(drop.item());
        record(entry.name, drop.count());
        TrackerStore.record("farmdrops", entry.id, drop.count());
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][FarmDrops] {} x{} ({}, MF {})", entry.name,
                drop.count(), drop.tier(), drop.magicFind());
        int channels = drop.tier().atLeastVeryRare() ? cfg().farmDropVeryRareChannels
                : cfg().farmDropRareChannels;
        if (AlertChannels.any(channels)) {
            Long price = ItemAppraisal.price(entry.id, ItemAppraisal.Side.SELL);
            String detail = entry.name + (drop.count() > 1 ? " x" + drop.count() : "")
                    + (price == null ? "" : " · "
                    + sbs.modid.client.core.util.NumberDisplay.shorten((double) price * drop.count()));
            Alerts.send(Alerts.Alert.of(drop.tier().name().replace('_', ' ') + " DROP", detail),
                    channels);
        }
    }

    // ------------------------------------------------------------------ counters

    private synchronized void record(String name, int count) {
        ensureLoaded();
        if (session.isEmpty()) {
            sessionStartedAt = System.currentTimeMillis();
        }
        session.merge(name, count, Integer::sum);
        totals.merge(name, count, Integer::sum);
        save();
    }

    public synchronized Map<String, Integer> session() {
        return Map.copyOf(session);
    }

    public synchronized Map<String, Integer> totals() {
        ensureLoaded();
        return Map.copyOf(totals);
    }

    /** Drops per hour this session, or {@code -1} before an hour's worth of time can be judged. */
    public synchronized double perHour() {
        int n = session.values().stream().mapToInt(Integer::intValue).sum();
        long elapsed = System.currentTimeMillis() - sessionStartedAt;
        return n == 0 || elapsed < 60_000L ? -1 : n * 3_600_000.0 / elapsed;
    }

    /** Session value at the sell side; {@code unpriced} is set when an item had no price. */
    public synchronized long sessionValue(boolean[] unpriced) {
        long total = 0;
        FarmDropData list = DATA.get();
        for (var e : session.entrySet()) {
            FarmDropData.Entry entry = list == null ? null : list.byName(e.getKey());
            Long price = entry == null ? null : ItemAppraisal.price(entry.id, ItemAppraisal.Side.SELL);
            if (price == null) {
                unpriced[0] = true;
            } else {
                total += price * e.getValue();
            }
        }
        return total;
    }

    public synchronized void resetSession() {
        session.clear();
        sessionStartedAt = 0;
    }

    // ------------------------------------------------------------------ profile store

    private void ensureLoaded() {
        if (!loaded) {
            reloadProfile();
        }
    }

    @Override
    public synchronized void reloadProfile() {
        Map<String, Integer> read = new LinkedHashMap<>();
        try {
            Path path = ProfileContext.getInstance().file(FILE);
            if (Files.isRegularFile(path)) {
                Map<String, Integer> parsed = SBSFiles.GSON.fromJson(
                        Files.readString(path, StandardCharsets.UTF_8),
                        new com.google.gson.reflect.TypeToken<LinkedHashMap<String, Integer>>() { }
                                .getType());
                if (parsed != null) {
                    read.putAll(parsed);
                }
            }
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][FarmDrops] could not read {} ({})", FILE,
                    e.toString());
        }
        totals = read;
        loaded = true;
    }

    @Override
    public synchronized void flushProfile() {
        if (loaded) {
            save();
        }
    }

    private void save() {
        if (!ProfileContext.getInstance().known()) {
            return;
        }
        try {
            Path path = ProfileContext.getInstance().file(FILE);
            Files.createDirectories(path.getParent());
            Files.writeString(path, SBSFiles.GSON.toJson(totals), StandardCharsets.UTF_8);
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][FarmDrops] could not write {}", FILE, e);
        }
    }
}
