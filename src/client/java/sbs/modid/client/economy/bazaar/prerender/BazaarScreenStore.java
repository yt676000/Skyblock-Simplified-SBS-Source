/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.bazaar.prerender;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.SBSFiles;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The on-disk cache of captured Bazaar screens: one gzipped JSON document, versioned and capped.
 *
 * <p><b>Overwrite, never merge.</b> A screen captured again replaces what was there. There is no
 * history and no reconciliation, because the only claim this cache is allowed to make is "this is
 * what the screen looked like the last time it was seen" — merging two observations would produce a
 * layout that never existed, and that layout would then be shown to a player as a preview.
 *
 * <p><b>Bounded on both axes.</b> Screen count and total bytes, because the product pages are the
 * whole sizing question: categories and navigation come to well under a megabyte, while a product
 * page per Bazaar item is roughly fifty times that. When a cap is hit the oldest capture is dropped
 * first — the least likely to still be accurate anyway.
 *
 * <p><b>Gzipped</b> because the content is item lore, which is about as repetitive as text gets.
 *
 * <p>Global, not profile-scoped, and that is a deliberate narrowing rather than an assumption:
 * only screens that are pure layout are written here. Anything carrying the player's own orders —
 * product pages and every confirm flow — is refused by {@link BazaarPrerender}, so nothing
 * account-specific reaches this file. Widening that is a phase-2 decision that needs
 * {@code ProfileScopedStore}, not a bigger cap here.
 */
public final class BazaarScreenStore {

    /** Screens kept at once. Categories and navigation are a couple of dozen; this is room to spare. */
    public static final int MAX_SCREENS = 64;

    /** Hard ceiling on the uncompressed document, before gzip. Roughly 800 bytes a slot at 54 slots. */
    public static final long MAX_BYTES = 4L * 1024 * 1024;

    private static final BazaarScreenStore INSTANCE = new BazaarScreenStore();

    /** Key to capture. Insertion-ordered so the eviction pass has something stable to walk. */
    private final Map<String, CapturedScreen> screens = new LinkedHashMap<>();

    private boolean loaded;
    private boolean dirty;

    private BazaarScreenStore() {
    }

    public static BazaarScreenStore getInstance() {
        return INSTANCE;
    }

    /** {@code config/sbs/economy/bazaar-prerender.json.gz}. */
    private static Path file() {
        return SBSFiles.economyDir().resolve("bazaar-prerender.json.gz");
    }

    /**
     * The capture stored under a key, or {@code null}.
     *
     * <p>Does not load from disk on its own — {@link #load()} is called once when the feature is
     * switched on. With the feature off this returns {@code null} for everything and touches nothing.
     */
    public synchronized CapturedScreen get(String key) {
        return key == null ? null : screens.get(key);
    }

    public synchronized int size() {
        return screens.size();
    }

    /** Every capture, for the diagnostics summary. */
    public synchronized List<CapturedScreen> all() {
        return new ArrayList<>(screens.values());
    }

    /**
     * Stores a capture, replacing any previous one under the same key.
     *
     * <p>Carries the observed click transitions across from the capture being replaced: those are
     * learned by watching and are expensive to relearn, while the slots they belong to are refreshed
     * every visit. Losing them on every re-capture would mean the click lookup never accumulates.
     */
    public synchronized void put(CapturedScreen screen) {
        if (screen == null || screen.key == null || screen.key.isEmpty()) {
            return;
        }
        CapturedScreen previous = screens.remove(screen.key);
        if (previous != null) {
            previous.opensOnClick.forEach(screen.opensOnClick::putIfAbsent);
        }
        screens.put(screen.key, screen);
        dirty = true;
        evict();
    }

    /** Drops oldest-first until both caps hold. */
    private void evict() {
        if (screens.size() > MAX_SCREENS) {
            List<CapturedScreen> byAge = new ArrayList<>(screens.values());
            byAge.sort(Comparator.comparingLong(s -> s.capturedAtMs));
            for (int i = 0; i < byAge.size() && screens.size() > MAX_SCREENS; i++) {
                screens.remove(byAge.get(i).key);
            }
        }
        long total = approximateBytes();
        if (total <= MAX_BYTES) {
            return;
        }
        List<CapturedScreen> byAge = new ArrayList<>(screens.values());
        byAge.sort(Comparator.comparingLong(s -> s.capturedAtMs));
        for (CapturedScreen oldest : byAge) {
            if (total <= MAX_BYTES || screens.size() <= 1) {
                break;
            }
            total -= oldest.approximateBytes();
            screens.remove(oldest.key);
        }
    }

    /** Sum of the stored captures' approximate uncompressed size. */
    public synchronized long approximateBytes() {
        long total = 0;
        for (CapturedScreen screen : screens.values()) {
            total += screen.approximateBytes();
        }
        return total;
    }

    /**
     * Reads the cache once.
     *
     * <p>A document written by another format version is <b>discarded, not migrated</b>: the layout
     * it describes would be decoded under assumptions this build does not hold, and the failure mode
     * of getting that wrong is a plausible-looking screen with the wrong items in it.
     */
    public synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        Path path = file();
        if (!Files.exists(path)) {
            return;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(path)), StandardCharsets.UTF_8))) {
            java.lang.reflect.Type type =
                    new com.google.gson.reflect.TypeToken<List<CapturedScreen>>() { }.getType();
            List<CapturedScreen> list = SBSFiles.GSON.fromJson(reader, type);
            if (list == null) {
                return;
            }
            int discarded = 0;
            for (CapturedScreen screen : list) {
                if (screen == null || screen.key == null || screen.key.isEmpty()) {
                    continue;
                }
                if (screen.formatVersion != BazaarScreenKey.FORMAT_VERSION) {
                    discarded++;
                    continue;
                }
                screens.put(screen.key, screen);
            }
            if (discarded > 0) {
                SkyblockSimplifiedSBS.LOGGER.info(
                        "[SBS][BzCache] discarded {} capture(s) written for an older format version",
                        discarded);
            }
        } catch (Throwable t) {
            // A corrupt cache is expected and recoverable: the feature simply has nothing to preview
            // from until the screens are seen again. Logged at info for the same reason data loading
            // is elsewhere in the mod - the fallback is already serving.
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][BzCache] cache unreadable, starting empty ({})",
                    t.toString());
            screens.clear();
        }
    }

    /** Writes the cache if anything changed. */
    public synchronized void save() {
        if (!dirty) {
            return;
        }
        dirty = false;
        try {
            Path path = file();
            Files.createDirectories(path.getParent());
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                    new GZIPOutputStream(Files.newOutputStream(path)), StandardCharsets.UTF_8))) {
                SBSFiles.GSON.toJson(new ArrayList<>(screens.values()), writer);
            }
        } catch (Throwable t) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][BzCache] could not write the screen cache", t);
        }
    }

    /** Forgets everything, on disk and in memory. */
    public synchronized void clear() {
        screens.clear();
        dirty = true;
        save();
    }
}
