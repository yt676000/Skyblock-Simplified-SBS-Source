/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.farming.logic;

import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ProfileContext;
import sbs.modid.client.core.config.ProfileScopedStore;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.skills.farming.model.Farm;
import sbs.modid.client.skills.farming.model.Lane;
import sbs.modid.client.skills.farming.model.LaneArea;
import sbs.modid.client.skills.garden.model.GardenPlot;
import sbs.modid.client.skills.garden.model.GardenPlotCatalog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The farms and lanes the player marked, per account + SkyBlock profile ({@code lane-farms.json}),
 * plus what is being marked right now (memory only - a half-marked lane is not worth persisting).
 * A Garden layout belongs to one profile, so another profile's lanes would warn at ends that are not
 * there.
 *
 * <p><b>Migration.</b> Before farms, each profile had {@code lane-areas.json}: a list of rectangles.
 * When {@code lane-farms.json} does not exist yet and that file does, every rectangle becomes a farm
 * holding one rectangle lane group ({@link LaneFarms#fromAreas}) and the new file is written at once.
 * The old file is left where it is, untouched, so a downgrade still finds it; it is never read again
 * once the new file exists.
 */
public final class LaneAreaStore implements ProfileScopedStore {

    private static final LaneAreaStore INSTANCE = new LaneAreaStore();

    static final String FILE = "lane-farms.json";
    static final String LEGACY_FILE = "lane-areas.json";

    /** What {@code lane-farms.json} holds. */
    private static final class Saved {
        int version = 1;
        int nextId = 1;
        /** The farm last selected or marked into; 0 = none. */
        int selected;
        /** "Show only this farm" in the preview; 0 = show all. */
        int onlyFarm;
        List<Farm> farms = new ArrayList<>();
    }

    private Saved saved = new Saved();
    private boolean loaded;

    /** Lane start while a lane is being marked, or {@code null}. {x, y, z}. */
    private int[] pendingStart;
    /** The farm the pending lane goes into. */
    private int pendingFarm;
    /** Rectangle corner 1 while a rectangle is being marked, or {@code null}. {x, y, z}. */
    private int[] pendingCorner;

    private LaneAreaStore() {
        ProfileContext.getInstance().register(this);
    }

    public static LaneAreaStore getInstance() {
        return INSTANCE;
    }

    /** The plot number at a block, {@code -1} off the plot grid. */
    public static int plotAt(int x, int z) {
        return GardenPlotCatalog.numberAt(GardenPlot.cell(x), GardenPlot.cell(z));
    }

    public List<Farm> farms() {
        load();
        return saved.farms;
    }

    public Farm farm(int id) {
        for (Farm farm : farms()) {
            if (farm.id == id) {
                return farm;
            }
        }
        return null;
    }

    public int selectedId() {
        load();
        return saved.selected;
    }

    public void select(Farm farm) {
        load();
        saved.selected = farm == null ? 0 : farm.id;
        save();
    }

    /** The farm marking goes into at a position ({@link LaneFarms#current}). */
    public Farm current(int x, int z) {
        return LaneFarms.current(farms(), plotAt(x, z), selectedId());
    }

    public int onlyFarm() {
        load();
        return saved.onlyFarm;
    }

    public void setOnlyFarm(int id) {
        load();
        saved.onlyFarm = id;
        save();
    }

    public Farm newFarm(String name, int plot) {
        load();
        Farm farm = new Farm(saved.nextId++, LaneFarms.freeName(saved.farms, name), plot);
        saved.farms.add(farm);
        saved.selected = farm.id;
        save();
        return farm;
    }

    public void addLanes(Farm farm, List<Lane> lanes) {
        farm.lanes.addAll(lanes);
        saved.selected = farm.id;
        save();
    }

    public void removeLane(Farm farm, Lane lane) {
        farm.lanes.remove(lane);
        save();
    }

    public void renameFarm(Farm farm, String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        farm.name = name.trim();
        save();
    }

    public void removeFarm(Farm farm) {
        load();
        saved.farms.remove(farm);
        if (saved.selected == farm.id) {
            saved.selected = 0;
        }
        if (saved.onlyFarm == farm.id) {
            saved.onlyFarm = 0;
        }
        if (pendingFarm == farm.id) {
            pendingStart = null;
        }
        save();
    }

    public void changed() {
        save();
    }

    // ------------------------------------------------------------------ marking in progress

    public int[] pendingStart() {
        return pendingStart;
    }

    public int pendingFarm() {
        return pendingFarm;
    }

    public void setPendingStart(int[] start, int farmId) {
        pendingStart = start;
        pendingFarm = farmId;
    }

    public int[] pendingCorner() {
        return pendingCorner;
    }

    public void setPendingCorner(int[] corner) {
        pendingCorner = corner;
    }

    // ------------------------------------------------------------------ persistence

    private void load() {
        if (loaded) {
            return;
        }
        if (!ProfileContext.getInstance().known()) {
            return;   // try again once the profile is known
        }
        loaded = true;
        Path path = ProfileContext.getInstance().file(FILE);
        try {
            if (Files.isRegularFile(path)) {
                Saved read = SBSFiles.GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), Saved.class);
                if (read != null) {
                    saved = sanitised(read);
                }
                return;
            }
        } catch (IOException | JsonSyntaxException e) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Lane] lane farms unreadable, starting empty (copy kept as "
                    + "{}.unreadable): {}", FILE, e.toString());
            try {
                // The next save overwrites the file; keep what was there for a hand repair.
                Files.copy(path, path.resolveSibling(FILE + ".unreadable"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException copy) {
                SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Lane] could not keep the unreadable lane farms", copy);
            }
            return;   // never migrate over a file that exists but did not parse
        }
        migrateLegacy();
    }

    private static Saved sanitised(Saved read) {
        if (read.farms == null) {
            read.farms = new ArrayList<>();
        }
        read.farms.removeIf(f -> f == null);
        int maxId = 0;
        for (Farm farm : read.farms) {
            if (farm.lanes == null) {
                farm.lanes = new ArrayList<>();
            }
            farm.lanes.removeIf(l -> l == null);
            if (farm.name == null) {
                farm.name = "";
            }
            maxId = Math.max(maxId, farm.id);
        }
        read.nextId = Math.max(read.nextId, maxId + 1);
        return read;
    }

    private void migrateLegacy() {
        Path legacy = ProfileContext.getInstance().file(LEGACY_FILE);
        if (!Files.isRegularFile(legacy)) {
            return;
        }
        try {
            List<LaneArea> areas = SBSFiles.GSON.fromJson(Files.readString(legacy, StandardCharsets.UTF_8),
                    new TypeToken<List<LaneArea>>() { }.getType());
            if (areas == null || areas.isEmpty()) {
                return;
            }
            saved.farms = LaneFarms.fromAreas(areas, () -> saved.nextId++, LaneAreaStore::plotAt);
            save();
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Lane] migrated {} lane area(s) into farms; {} kept as it was",
                    saved.farms.size(), LEGACY_FILE);
        } catch (IOException | JsonSyntaxException e) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Lane] old lane areas unreadable, not migrated: {}", e.toString());
        }
    }

    private void save() {
        if (!loaded || !ProfileContext.getInstance().known()) {
            return;
        }
        try {
            Path path = ProfileContext.getInstance().file(FILE);
            Files.createDirectories(path.getParent());
            Files.writeString(path, SBSFiles.GSON.toJson(saved), StandardCharsets.UTF_8);
        } catch (IOException e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Lane] could not write lane farms", e);
        }
    }

    @Override
    public void flushProfile() {
        if (loaded) {
            save();
        }
    }

    @Override
    public void reloadProfile() {
        saved = new Saved();
        pendingStart = null;
        pendingCorner = null;
        loaded = false;
        load();
    }
}
