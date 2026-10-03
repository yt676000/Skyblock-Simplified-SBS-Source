/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.museum.logic;

import com.google.gson.JsonSyntaxException;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ProfileContext;
import sbs.modid.client.core.config.ProfileScopedStore;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.helper.museum.model.MuseumCatalog.Category;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the Museum menu showed as donated, per account and SkyBlock profile - a museum belongs to one
 * profile - kept page by page with the time each page was seen.
 *
 * <p><b>Page by page, replaced on every visit.</b> A page's donated set is overwritten each time it
 * is read, so an item taken back out of the Museum drops out on the next visit rather than being
 * remembered forever.
 *
 * <p><b>Known means every page.</b> A category is only {@link #known} once all of its pages have
 * been seen: "not donated" read off half a category would be a confident wrong answer, and a
 * confident wrong answer is exactly what the tooltip line must never print.
 *
 * <p>Follows the profile-store rules the storage cache paid for: nothing is latched as loaded on a
 * missing file while the profile is still unknown, nothing is written under the {@code default}
 * placeholder, a file that could not be read is never overwritten, and readers see nothing until the
 * profile is known.
 */
public final class MuseumStore implements ProfileScopedStore {

    private static final MuseumStore INSTANCE = new MuseumStore();

    private static final String FILE = "museum.json";
    private static final int SCHEMA_VERSION = 1;

    /** The file, as Gson sees it. Field names are the file format. */
    static final class Data {
        int schemaVersion = SCHEMA_VERSION;
        Map<String, CategoryState> categories = new LinkedHashMap<>();
    }

    static final class CategoryState {
        int totalPages;
        Map<Integer, Page> pages = new LinkedHashMap<>();
    }

    static final class Page {
        long seenAt;
        List<String> donated = new ArrayList<>();
    }

    private Data data = new Data();
    private boolean loaded;
    /** Set when a file existed and could not be read: that file is never written over. */
    private boolean unreadable;
    private final Map<Category, Set<String>> donatedCache = new EnumMap<>(Category.class);
    private volatile int generation;

    private MuseumStore() {
        ProfileContext.getInstance().register(this);
    }

    public static MuseumStore getInstance() {
        return INSTANCE;
    }

    /** Bumped on every change and reload, for anything caching a derived view. */
    public int generation() {
        return generation;
    }

    // ------------------------------------------------------------------ reads

    /** Whether every page of {@code category} has been seen for this profile. */
    public synchronized boolean known(Category category) {
        if (!ready()) {
            return false;
        }
        CategoryState state = data.categories.get(category.name());
        if (state == null || state.totalPages <= 0) {
            return false;
        }
        for (int page = 1; page <= state.totalPages; page++) {
            if (!state.pages.containsKey(page)) {
                return false;
            }
        }
        return true;
    }

    /** Pages seen / pages the category has, for the "visit the rest" hint; {0, 0} when never seen. */
    public synchronized int[] coverage(Category category) {
        if (!ready()) {
            return new int[] {0, 0};
        }
        CategoryState state = data.categories.get(category.name());
        if (state == null) {
            return new int[] {0, 0};
        }
        int seen = 0;
        for (int page = 1; page <= state.totalPages; page++) {
            if (state.pages.containsKey(page)) {
                seen++;
            }
        }
        return new int[] {seen, state.totalPages};
    }

    /**
     * {@code TRUE} donated, {@code FALSE} not donated, {@code null} unknown (the category has not
     * been fully seen, or the profile is not known yet).
     */
    public synchronized Boolean donated(Category category, String key) {
        if (!known(category)) {
            return null;
        }
        return donatedKeys(category).contains(key);
    }

    /** When the oldest page of a known category was read; {@code 0} when not known. */
    public synchronized long seenAt(Category category) {
        if (!known(category)) {
            return 0L;
        }
        long oldest = Long.MAX_VALUE;
        for (Page page : data.categories.get(category.name()).pages.values()) {
            oldest = Math.min(oldest, page.seenAt);
        }
        return oldest == Long.MAX_VALUE ? 0L : oldest;
    }

    private Set<String> donatedKeys(Category category) {
        return donatedCache.computeIfAbsent(category, c -> {
            Set<String> keys = new HashSet<>();
            CategoryState state = data.categories.get(c.name());
            if (state != null) {
                for (Page page : state.pages.values()) {
                    keys.addAll(page.donated);
                }
            }
            return keys;
        });
    }

    private boolean ready() {
        ensureLoaded();
        return loaded && ProfileContext.getInstance().known();
    }

    // ------------------------------------------------------------------ writes

    /** One museum page as it was just read: replaces that page, and the page count if it changed. */
    public synchronized void recordPage(Category category, int page, int totalPages,
                                        Collection<String> donated) {
        if (!ready() || page < 1 || totalPages < page) {
            return;
        }
        CategoryState state = data.categories.computeIfAbsent(category.name(), k -> new CategoryState());
        if (state.totalPages != totalPages) {
            // Hypixel added or removed a page: pages past the new end no longer exist.
            state.pages.keySet().removeIf(p -> p > totalPages);
            state.totalPages = totalPages;
        }
        Page fresh = new Page();
        fresh.seenAt = System.currentTimeMillis();
        fresh.donated = new ArrayList<>(donated);
        state.pages.put(page, fresh);
        donatedCache.remove(category);
        generation++;
        save();
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
        data = new Data();
        donatedCache.clear();
        unreadable = false;
        loaded = false;
        generation++;
        if (!ProfileContext.getInstance().known()) {
            return;   // the path is the placeholder's, not this profile's: retry once it is known
        }
        Path path = file();
        if (!Files.isRegularFile(path)) {
            loaded = true;   // a known profile with no file yet: nothing seen, and free to write
            return;
        }
        try {
            Data read = SBSFiles.GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), Data.class);
            if (read != null) {
                if (read.categories == null) {
                    read.categories = new LinkedHashMap<>();
                }
                for (CategoryState state : read.categories.values()) {
                    if (state != null && state.pages == null) {
                        state.pages = new LinkedHashMap<>();
                    }
                }
                read.categories.values().removeIf(state -> state == null);
                if (read.schemaVersion > SCHEMA_VERSION) {
                    unreadable = true;   // a newer client's file: use it, never overwrite it
                }
                data = read;
            }
            loaded = true;
        } catch (JsonSyntaxException corrupt) {
            // A corrupt file never heals by itself: latch, and keep it on disk untouched.
            unreadable = true;
            loaded = true;
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Museum] {} is unreadable ({}) - left as it is, "
                    + "museum pages will be re-learned but not saved", FILE, corrupt.toString());
        } catch (Exception transientFailure) {
            // A read error can be transient: leave loaded false so the next read retries.
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Museum] could not read {} yet ({})", FILE,
                    transientFailure.toString());
        }
    }

    @Override
    public synchronized void flushProfile() {
        if (loaded) {
            save();
        }
    }

    private void save() {
        if (!loaded || unreadable || !ProfileContext.getInstance().known()) {
            return;
        }
        try {
            Path path = file();
            Files.createDirectories(path.getParent());
            data.schemaVersion = SCHEMA_VERSION;
            Files.writeString(path, SBSFiles.GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Museum] could not write {}: {}", FILE, e.toString());
        }
    }
}
