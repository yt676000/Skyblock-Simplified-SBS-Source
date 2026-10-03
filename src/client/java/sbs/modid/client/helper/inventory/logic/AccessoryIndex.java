/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.inventory.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ProfileContext;
import sbs.modid.client.core.config.ProfileScopedStore;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.core.item.SkyblockItem;
import sbs.modid.client.ui.render.MenuFrame;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which accessories this profile owns, learned by watching the Accessory Bag.
 *
 * <p><b>What it can know.</b> Hypixel's bag is a server-side menu, so the client only learns its
 * contents by being shown them. There is no request that returns "your accessories" and no packet
 * that volunteers them - the honest model is therefore the same one the storage search already
 * uses: record each page as it is opened, keep it, and be explicit in the UI about what has not
 * been seen yet. A first-run player is told to flip through their bag rather than being shown a
 * confident list of 400 "missing" accessories they may well already own.
 *
 * <p><b>Freshness.</b> Records are keyed by bag page, so re-opening a page <i>replaces</i> it
 * wholesale. Selling an accessory therefore un-owns it the next time that page is looked at, with
 * no polling and no stale entry that can only be cleared by hand.
 *
 * <p><b>Recombobulation is recorded per item</b>, because it moves an accessory a rarity step up and
 * so changes its Magical Power. Ignoring it would under-report the total for anyone who has spent
 * on their bag, which is precisely the audience for this feature.
 *
 * <p>Profile-scoped: a bag belongs to one account plus one SkyBlock profile, and reading another
 * profile's bag into this one would be a silent correctness bug rather than a cosmetic one.
 */
public final class AccessoryIndex implements ProfileScopedStore {

    /** "Accessory Bag (2/4)" - the page marker Hypixel puts in the title of a multi-page bag. */
    private static final Pattern PAGE_MARKER = Pattern.compile("\\((\\d+)\\s*/\\s*(\\d+)\\)");

    /** Title fragment that identifies the bag. Lower-cased and colour-stripped before matching. */
    private static final String BAG_TITLE = "accessory bag";

    private static final AccessoryIndex INSTANCE = new AccessoryIndex();

    /** One accessory as it sits in the bag. */
    public record Owned(String id, boolean recombobulated) {
    }

    /** Bag page number -> the accessories on it, replaced wholesale each time the page is seen. */
    private final Map<Integer, List<Owned>> pages = new ConcurrentHashMap<>();

    /** Page number -> when it was last read, so the UI can say how stale the answer is. */
    private final Map<Integer, Long> seenAt = new ConcurrentHashMap<>();

    /** The highest page count the bag has ever reported, or {@code 0} when it never reported one. */
    private volatile int totalPages;

    /**
     * Whether a bag title has ever carried a "(2/4)" page marker.
     *
     * <p>This is what separates "one page, all of it seen" from "several pages whose numbering we
     * failed to read, all overwriting each other as page 1". Without it the second case reports
     * itself as a complete bag, which is the worst thing this feature could do: a confident missing
     * list built on a third of the data. When no marker has ever been seen, coverage is reported as
     * unproven rather than complete.
     */
    private volatile boolean sawPageMarker;

    /** Menu state id per page, so a page merely being looked at is not re-copied 60 times a second. */
    private final Map<Integer, Integer> stateIds = new ConcurrentHashMap<>();

    private volatile boolean loaded;
    /** A save inside the interval is deferred to {@link #tick}, never dropped. */
    private final sbs.modid.client.core.config.SaveThrottle saveThrottle =
            new sbs.modid.client.core.config.SaveThrottle();

    private AccessoryIndex() {
        // Registering from the constructor (not the client initializer) is the project rule: doing
        // it early forces these classes to load before the item registry is bound.
        ProfileContext.getInstance().register(this);
    }

    public static AccessoryIndex getInstance() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------
    // Capture
    // ------------------------------------------------------------------

    /**
     * Whether this menu is the Accessory Bag. <b>The one test</b> - the chip above the menu, the
     * duplicate highlight and the render tier that decides whether either runs all ask this, so
     * there is no way for two of them to disagree about which screen the bag is.
     *
     * <p>It reads {@link MenuFrame}'s cached title, which is stripped and lowercased once per frame
     * for the whole mod. This used to be three separate copies, and the cheapest of them still ran a
     * regex on every container frame in the game.
     */
    public static boolean isBagMenu(MenuFrame frame) {
        return frame != null && frame.titleContains(BAG_TITLE);
    }

    /**
     * Records the open screen if it is an Accessory Bag page.
     *
     * <p>Called from the container render hook, so it runs on every frame a container is open and
     * has to be nearly free in the common case. It bails on {@link #isBagMenu} for anything that is
     * not the bag, and then on the menu's {@link AbstractContainerMenu#getStateId() state id} -
     * which the server bumps whenever the contents actually change - so a page is copied once, not
     * per frame, while a real change is still picked up on the very next frame.
     */
    public void capture(AbstractContainerScreen<?> screen) {
        MenuFrame frame = MenuFrame.of(screen);
        if (!isBagMenu(frame)) {
            return;
        }
        String title = frame.normalised();
        load();
        int page = 1;
        Matcher matcher = PAGE_MARKER.matcher(title);
        if (matcher.find()) {
            page = Integer.parseInt(matcher.group(1));
            totalPages = Math.max(totalPages, Integer.parseInt(matcher.group(2)));
            sawPageMarker = true;
        } else {
            // No marker: either a single-page bag or a wording we do not match. Both land here as
            // page 1, so complete() refuses to call the bag fully read - see sawPageMarker.
            totalPages = Math.max(totalPages, 1);
        }

        AbstractContainerMenu menu = screen.getMenu();
        Integer previous = stateIds.get(page);
        if (previous != null && previous == menu.getStateId()) {
            return;
        }

        List<Owned> found = new ArrayList<>();
        for (Slot slot : menu.slots) {
            // The player-inventory slots at the bottom are not the bag; an accessory carried there
            // is counted separately by carried(), never as a bag page's contents.
            if (slot.container instanceof Inventory) {
                continue;
            }
            Owned owned = read(slot.getItem());
            if (owned != null) {
                found.add(owned);
            }
        }
        stateIds.put(page, menu.getStateId());
        pages.put(page, found);
        seenAt.put(page, System.currentTimeMillis());
        save();
    }

    /**
     * One slot as an owned accessory, or {@code null} when it is not one. Menu chrome (filler panes,
     * page buttons) carries no SkyBlock id and so is skipped without a name check.
     */
    private static Owned read(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        CompoundTag extra = SkyblockItem.extraAttributes(stack);
        String id = extra.getStringOr("id", "");
        if (id.isEmpty() || !AccessoryCatalog.isAccessory(id)) {
            return null;
        }
        return new Owned(id, extra.getIntOr("rarity_upgrades", 0) > 0);
    }

    /**
     * Accessories the player is carrying in their inventory rather than keeping in the bag. Read
     * live on every query, never snapshotted - the inventory is already in the client.
     */
    public List<Owned> carried() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return List.of();
        }
        Inventory inventory = minecraft.player.getInventory();
        List<Owned> out = new ArrayList<>();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            Owned owned = read(inventory.getItem(i));
            if (owned != null) {
                out.add(owned);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Query
    // ------------------------------------------------------------------

    /**
     * Every accessory this profile is known to own, merged across the seen bag pages and the live
     * inventory. An id present twice keeps the recombobulated copy, since that is the one that
     * decides the Magical Power.
     */
    public Map<String, Owned> owned() {
        load();
        Map<String, Owned> merged = new LinkedHashMap<>();
        for (List<Owned> page : pages.values()) {
            for (Owned owned : page) {
                merge(merged, owned);
            }
        }
        for (Owned owned : carried()) {
            merge(merged, owned);
        }
        return merged;
    }

    private static void merge(Map<String, Owned> into, Owned owned) {
        Owned existing = into.get(owned.id());
        if (existing == null || (!existing.recombobulated() && owned.recombobulated())) {
            into.put(owned.id(), owned);
        }
    }

    /** How many copies of each accessory id have been seen, for the duplicate count. */
    public Map<String, Integer> counts() {
        load();
        Map<String, Integer> out = new HashMap<>();
        for (List<Owned> page : pages.values()) {
            for (Owned owned : page) {
                out.merge(owned.id(), 1, Integer::sum);
            }
        }
        return out;
    }

    /** How many bag pages have been read. */
    public int pagesSeen() {
        load();
        return pages.size();
    }

    /** How many pages the bag says it has, or {@code 0} when it never said. */
    public int pagesTotal() {
        load();
        return totalPages;
    }

    /**
     * Whether every page of the bag has been read, so the missing list can be presented as exact
     * rather than as an upper bound.
     *
     * <p>Requires a page marker to have been seen at least once. A bag whose numbering was never
     * read might be a single page (complete) or several pages that have all been recorded on top of
     * one another (badly incomplete), and nothing here can tell those apart - so it claims neither.
     */
    public boolean complete() {
        load();
        return sawPageMarker && pages.size() >= totalPages;
    }

    /** Whether the bag has ever told us how many pages it has. See {@link #complete()}. */
    public boolean pageCountKnown() {
        load();
        return sawPageMarker;
    }

    /** When the most recently read page was read, or {@code 0} when nothing has been. */
    public long lastSeen() {
        load();
        long latest = 0;
        for (long at : seenAt.values()) {
            latest = Math.max(latest, at);
        }
        return latest;
    }

    /** Forgets every page. The manual reset for a bag the player has reorganised wholesale. */
    public void clear() {
        pages.clear();
        seenAt.clear();
        stateIds.clear();
        totalPages = 0;
        sawPageMarker = false;
        writeCache();
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    /** The on-disk shape. Deliberately minimal: ids and flags, no item stacks. */
    private record PersistedPage(int page, long seenAt, List<String> ids, List<String> recombobulated) {
    }

    private record PersistedIndex(int totalPages, boolean sawPageMarker, List<PersistedPage> pages) {
    }

    private synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        java.nio.file.Path path = SBSFiles.accessoryCacheFile();
        try {
            if (!java.nio.file.Files.isRegularFile(path)) {
                return;
            }
            PersistedIndex stored;
            try (var reader = java.nio.file.Files.newBufferedReader(path)) {
                stored = SBSFiles.GSON.fromJson(reader, PersistedIndex.class);
            }
            if (stored == null || stored.pages() == null) {
                return;
            }
            totalPages = Math.max(0, stored.totalPages());
            sawPageMarker = stored.sawPageMarker();
            for (PersistedPage page : stored.pages()) {
                if (page == null || page.ids() == null) {
                    continue;
                }
                List<String> recomb = page.recombobulated() == null ? List.of() : page.recombobulated();
                List<Owned> owned = new ArrayList<>(page.ids().size());
                for (String id : page.ids()) {
                    owned.add(new Owned(id, recomb.contains(id)));
                }
                pages.put(page.page(), owned);
                seenAt.put(page.page(), page.seenAt());
            }
        } catch (Exception e) {
            // A corrupt cache is expected and survivable - the player re-opens the bag and it
            // refills. Logged at info because nothing is broken.
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Accessories] cache unreadable ({}), starting empty",
                    e.toString());
        }
    }

    /** Writes at most once every few seconds; page captures can land in bursts while paging. */
    private void save() {
        long now = System.currentTimeMillis();
        if (saveThrottle.request(false, now)) {
            saveThrottle.written(now);
            writeCache();
        }
    }

    /** Client tick: writes a change the throttle deferred, once its interval has passed. */
    public void tick() {
        long now = System.currentTimeMillis();
        if (saveThrottle.pending(now)) {
            saveThrottle.written(now);
            writeCache();
        }
    }

    private void writeCache() {
        List<PersistedPage> out = new ArrayList<>(pages.size());
        for (Map.Entry<Integer, List<Owned>> entry : pages.entrySet()) {
            List<String> ids = new ArrayList<>(entry.getValue().size());
            List<String> recomb = new ArrayList<>();
            for (Owned owned : entry.getValue()) {
                ids.add(owned.id());
                if (owned.recombobulated()) {
                    recomb.add(owned.id());
                }
            }
            out.add(new PersistedPage(entry.getKey(),
                    seenAt.getOrDefault(entry.getKey(), 0L), ids, recomb));
        }
        try {
            java.nio.file.Path path = SBSFiles.accessoryCacheFile();
            SBSFiles.ensureParent(path);
            try (var writer = java.nio.file.Files.newBufferedWriter(path)) {
                SBSFiles.GSON.toJson(new PersistedIndex(totalPages, sawPageMarker, out), writer);
            }
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Accessories] could not write cache: {}", e.toString());
        }
    }

    @Override
    public void flushProfile() {
        saveThrottle.written(System.currentTimeMillis());
        writeCache();
    }

    @Override
    public void reloadProfile() {
        synchronized (this) {
            pages.clear();
            seenAt.clear();
            stateIds.clear();
            totalPages = 0;
            sawPageMarker = false;
            loaded = false;
        }
        load();
    }
}
