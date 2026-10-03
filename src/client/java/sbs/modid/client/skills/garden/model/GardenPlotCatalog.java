/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.garden.model;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ProfileContext;
import sbs.modid.client.core.config.ProfileScopedStore;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.skills.farming.model.FarmingText;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Garden's plot grid: Hypixel's fixed numbering, plus the plot icons learned from the
 * Configure Plots menu.
 *
 * <p><b>The numbering is a constant, not a guess.</b> Hypixel lays the 24 plots out in one fixed
 * 5×5 arrangement around the Garden house (verified against the game's own plot map): the four
 * plots touching the house sides are 1-4, the diagonals 5-8, then outward ring by ring to the
 * corners 21-24. The house itself sits in the middle cell - it is no plot, has no number and can
 * never be infested. Combined with {@link GardenPlot} (96-block cells centred on the world origin,
 * the house's own centre) every plot number maps straight to world coordinates, so the infested
 * plots the tab widget names can be outlined without a single pest being loaded and without the
 * Configure Plots menu ever having been opened.
 *
 * <p><b>Only the icons are learned.</b> The menu still contributes what cannot be derived: the item
 * Hypixel draws for each plot (the crop, the preset). Those are cosmetic, so an unlearned grid is
 * fully functional - it just shows numbers instead of icons. The learned icons are persisted per
 * account+profile ({@code garden_plots_cache.json}, full SNBT stacks like the storage cache), so
 * one visit to the menu keeps the icons across restarts.
 */
public final class GardenPlotCatalog implements ProfileScopedStore {

    private static final GardenPlotCatalog INSTANCE = new GardenPlotCatalog();

    /** Plots per side of the Garden's 5×5 arrangement. */
    public static final int GRID_SIZE = 5;

    /** The middle cell: the Garden house, not a plot. */
    public static final int HOUSE = 0;

    /**
     * Hypixel's fixed plot numbering, top row = north (row down = +Z, column right = +X).
     * {@code LAYOUT[cellZ + 2][cellX + 2]} is the plot number of that Garden cell.
     */
    private static final int[][] LAYOUT = {
            {21, 13,  9, 14, 22},
            {15,  5,  1,  6, 16},
            {10,  2, HOUSE, 3, 11},
            {17,  7,  4,  8, 18},
            {23, 19, 12, 20, 24},
    };

    /** The item name Hypixel gives a plot cell: "Plot - 18". */
    private static final Pattern PLOT_NAME = Pattern.compile("(?i)^plot\\s*[-–]?\\s*(\\d+)\\s*$");
    /** "Preset: Compact" in the lore. */
    private static final Pattern PRESET = Pattern.compile("(?i)^preset\\s*:\\s*(.+?)\\s*$");
    /** The menu is redrawn every frame; re-reading it that often would be pure waste. */
    private static final long RELEARN_INTERVAL_MS = 500L;
    /** Chest menus are 9 wide - the divisor that turns a slot index into a row/column. */
    private static final int CHEST_WIDTH = 9;

    /** What the menu taught us about one plot: its icon, its preset, and the icon's disk form. */
    private record Learned(ItemStack icon, String preset, String snbt) {
    }

    /** One plot as persisted: the icon as full SNBT so it redraws with the real SkyBlock texture. */
    private record PersistedPlot(int number, String preset, String snbt) {
    }

    private volatile Map<Integer, Learned> learned = Map.of();
    private long lastLearnAt;
    private volatile boolean loaded;

    private GardenPlotCatalog() {
        ProfileContext.getInstance().register(this);
    }

    public static GardenPlotCatalog getInstance() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------ the fixed layout

    /**
     * The plot number of a Garden cell, {@link #HOUSE} for the centre cell, or {@code -1} for a
     * cell outside the 5×5 plot area (the void beyond the Garden's edge).
     */
    public static int numberAt(int cellX, int cellZ) {
        if (cellX < -2 || cellX > 2 || cellZ < -2 || cellZ > 2) {
            return -1;
        }
        return LAYOUT[cellZ + 2][cellX + 2];
    }

    /**
     * The Garden grid cell a plot number sits in, as {@code {cellX, cellZ}} for {@link GardenPlot},
     * or {@code null} when the number is no plot (0, negatives, anything past 24).
     */
    public static int[] cellOf(int number) {
        if (number <= 0) {
            return null;
        }
        for (int row = 0; row < GRID_SIZE; row++) {
            for (int col = 0; col < GRID_SIZE; col++) {
                if (LAYOUT[row][col] == number) {
                    return new int[]{col - 2, row - 2};
                }
            }
        }
        return null;
    }

    /**
     * The plot number at a position of the 5×5 map as drawn (row 0 = top = north), {@link #HOUSE}
     * for the centre. For the overlay grid, which draws the map exactly like the world lies.
     */
    public static int numberAtGrid(int row, int col) {
        return LAYOUT[row][col];
    }

    // ------------------------------------------------------------------ learned icons

    /** Whether the given container title is the Configure Plots menu. */
    public static boolean isPlotsMenu(String rawTitle) {
        String title = FarmingText.strip(rawTitle == null ? "" : rawTitle).toLowerCase(Locale.ROOT);
        return title.contains("plot");
    }

    /**
     * Reads the plot icons out of the open Configure Plots menu. Safe to call every frame - it
     * throttles itself and ignores every other screen. Merges into what is already known, so a
     * plot that is locked (and therefore absent from the menu) keeps its previously seen icon.
     */
    public void learn(AbstractContainerMenu menu, String rawTitle) {
        if (menu == null || !isPlotsMenu(rawTitle)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastLearnAt < RELEARN_INTERVAL_MS) {
            return;
        }
        lastLearnAt = now;
        loadCache();

        // The player inventory is the trailing 36 slots; only the chest part holds plots.
        int containerSlots = Math.max(0, menu.getItems().size() - 36);
        Map<Integer, Learned> found = new HashMap<>(learned);
        boolean any = false;
        for (int i = 0; i < containerSlots; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            Matcher m = PLOT_NAME.matcher(FarmingText.strip(FarmingText.name(stack)).trim());
            if (!m.matches()) {
                continue;   // locked slot, filler pane, the back button - not a plot
            }
            int number = Integer.parseInt(m.group(1));
            if (cellOf(number) == null) {
                continue;   // not a number of the fixed layout - whatever it is, it is not a plot
            }
            found.put(number, new Learned(stack.copy(), preset(stack), encodeStack(stack)));
            any = true;
        }
        if (!any) {
            return;   // a "plot"-titled menu that is not the grid (a preset editor, say)
        }
        boolean changed = !samePersistedForm(learned, found);
        learned = Map.copyOf(found);
        if (changed) {
            writeCache();   // rare - only when an icon or preset actually changed
        }
    }

    /** Whether two learned maps would persist identically (the icon is compared via its SNBT). */
    private static boolean samePersistedForm(Map<Integer, Learned> a, Map<Integer, Learned> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (Map.Entry<Integer, Learned> entry : b.entrySet()) {
            Learned old = a.get(entry.getKey());
            if (old == null || !Objects.equals(old.preset(), entry.getValue().preset())
                    || !Objects.equals(old.snbt(), entry.getValue().snbt())) {
                return false;
            }
        }
        return true;
    }

    private static String preset(ItemStack stack) {
        for (String line : FarmingText.lore(stack)) {
            Matcher m = PRESET.matcher(FarmingText.strip(line).trim());
            if (m.matches()) {
                return m.group(1);
            }
        }
        return null;
    }

    /**
     * The menu's icon for a plot, or {@code null} while it has never been seen. A shared stack -
     * draw it, never mutate it.
     */
    public ItemStack icon(int number) {
        loadCache();
        Learned entry = learned.get(number);
        return entry == null || entry.icon() == null || entry.icon().isEmpty() ? null : entry.icon();
    }

    /** Whether any plot icons are known (learned this session or loaded from the profile cache). */
    public boolean hasIcons() {
        loadCache();
        return !learned.isEmpty();
    }

    // ------------------------------------------------------------------ persistence

    /** How often an absent cache is re-checked while waiting for the real profile to be resolved. */
    private static final long LOAD_RETRY_MS = 1_000L;

    /** When the last load was attempted, so a missing file is not stat-ed every frame. */
    private long lastLoadAttemptAt;

    /**
     * Loads the cached icons lazily, and <b>keeps trying until it actually reads the file</b>.
     *
     * <p>Two reasons a first attempt legitimately finds nothing, and neither may be treated as "this
     * profile has no plots":
     * <ul>
     *   <li>Decoding an SNBT stack needs a live level's registry context, so nothing can load before
     *       one is up.</li>
     *   <li><b>The profile is not known yet.</b> {@code ProfileContext} starts on {@code default} and
     *       only switches to the real profile once the tab list carries it - measured at ~15 s after
     *       joining. The cache lives under the real profile, so an attempt inside that window reads a
     *       path that does not exist.</li>
     * </ul>
     * Latching {@code loaded} on that empty attempt is what made the icons look unsaved: the profile
     * switch does re-trigger a load, but any read in between - the grid being opened, a
     * {@code hasIcons()} check - had already pinned the answer to "nothing known", and only opening
     * the Desk again could refill it. Now {@code loaded} is set only after a file is really read, so
     * the icons come back on their own.
     */
    private synchronized void loadCache() {
        if (loaded) {
            return;
        }
        if (Minecraft.getInstance().level == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastLoadAttemptAt < LOAD_RETRY_MS) {
            return;
        }
        lastLoadAttemptAt = now;
        Path path = SBSFiles.gardenPlotsCacheFile();
        try {
            if (!Files.exists(path)) {
                return;   // deliberately NOT latching - see above
            }
            java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<List<PersistedPlot>>() {
            }.getType();
            List<PersistedPlot> stored;
            try (var reader = Files.newBufferedReader(path)) {
                stored = SBSFiles.GSON.fromJson(reader, type);
            }
            if (stored == null) {
                loaded = true;   // the file is there but empty / unreadable JSON: nothing to wait for
                return;
            }
            Map<Integer, Learned> restored = new HashMap<>(learned);
            int failed = 0;
            for (PersistedPlot pp : stored) {
                if (cellOf(pp.number()) == null || restored.containsKey(pp.number())) {
                    continue;   // live-learned data beats the cache
                }
                ItemStack icon = decodeStack(pp.snbt());
                if (icon == null) {
                    failed++;   // counted, not silent: a decode that always fails looks identical
                    continue;   // to "never saved", and that cost a bug report once already
                }
                restored.put(pp.number(), new Learned(icon, pp.preset(), pp.snbt()));
            }
            learned = Map.copyOf(restored);
            loaded = true;
            SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][Garden] plot icons restored: {} of {} from {}{}",
                    learned.size(), stored.size(), path.getFileName(),
                    failed == 0 ? "" : " (" + failed + " failed to decode)");
        } catch (Exception e) {
            loaded = true;   // a broken file will not fix itself by being read again
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS] Failed to read garden plots cache: {}", e.toString());
        }
    }

    /**
     * Writes the learned icons to the current profile file.
     *
     * <p>Refuses while the profile is still unresolved. Icons belong to one profile's Garden, and
     * for the first ~15 s of a session {@code ProfileContext} is still on {@code default} - writing
     * then would file this Garden's plots under a profile that is not a profile, and the switch that
     * follows immediately reloads from the real one and drops them. Skipping costs nothing: the menu
     * is re-read every 500 ms while it is open, so the first write after the switch has the same
     * data.
     */
    private void writeCache() {
        if (ProfileContext.getInstance().profile().equals("default")) {
            return;
        }
        Map<Integer, Learned> snapshot = learned;
        List<PersistedPlot> out = new ArrayList<>(snapshot.size());
        for (Map.Entry<Integer, Learned> entry : snapshot.entrySet()) {
            String snbt = entry.getValue().snbt();
            if (snbt == null || snbt.isEmpty()) {
                continue;   // never persist an icon that failed to encode
            }
            out.add(new PersistedPlot(entry.getKey(), entry.getValue().preset(), snbt));
        }
        out.sort(java.util.Comparator.comparingInt(PersistedPlot::number));
        try {
            Path path = SBSFiles.gardenPlotsCacheFile();
            SBSFiles.ensureParent(path);
            try (var writer = Files.newBufferedWriter(path)) {
                SBSFiles.GSON.toJson(out, writer);
            }
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS] Failed to write garden plots cache: {}", e.toString());
        }
    }

    @Override
    public void flushProfile() {
        if (loaded && !learned.isEmpty()) {
            writeCache();
        }
    }

    @Override
    public void reloadProfile() {
        synchronized (this) {
            learned = Map.of();
            loaded = false;
            lastLoadAttemptAt = 0;   // a profile switch must read immediately, not wait out the retry
        }
        loadCache();
    }

    /** Encodes the full stack (all components) as SNBT, or {@code ""} when no level is up / it fails. */
    private static String encodeStack(ItemStack stack) {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level == null) {
                return "";
            }
            var ops = minecraft.level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
            return ItemStack.OPTIONAL_CODEC.encodeStart(ops, stack).result()
                    .map(Object::toString).orElse("");
        } catch (Throwable t) {
            return "";
        }
    }

    /** Decodes a persisted stack back to its full self ({@code null} on failure). */
    private static ItemStack decodeStack(String snbt) {
        if (snbt == null || snbt.isEmpty()) {
            return null;
        }
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level == null) {
                return null;
            }
            CompoundTag tag = TagParser.parseCompoundFully(snbt);
            var ops = minecraft.level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
            ItemStack stack = ItemStack.OPTIONAL_CODEC.parse(ops, tag).result().orElse(ItemStack.EMPTY);
            return stack.isEmpty() ? null : stack;
        } catch (Throwable t) {
            return null;
        }
    }
}
