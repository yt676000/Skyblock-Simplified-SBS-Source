/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.storage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.item.SkyblockItem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The central index behind the item search: every storage the client has seen, and the aggregation
 * that turns them into "you own 412 Enchanted Bread, across these five places".
 *
 * <p><b>What it can know.</b> Hypixel storages are server-side menus, so the client only learns a
 * container's contents by seeing it. The index therefore records each storage as it is opened and
 * keeps that snapshot for the session; the player inventory needs no snapshot at all and is read
 * live on every query. This mirrors how the existing hover preview already works, and is why the
 * search reports when a storage was last seen rather than pretending to be authoritative.
 *
 * <p><b>Freshness.</b> Snapshots are keyed by storage id, so re-opening a container replaces its
 * entry — the index self-updates the moment the player looks at a changed storage, with no polling.
 *
 * <p><b>Cost.</b> Capture is a shallow copy of one container, done only when a storage screen is
 * open. Searching walks the snapshots and is only ever run from the search screen, once per query
 * change, over at most a few thousand stacks. Nothing here touches the render or network hot path.
 *
 * <p>Thread-safety: snapshots live in a concurrent map; capture happens on the client thread and
 * queries on the render thread of the search screen.
 */
public final class StorageIndex implements sbs.modid.client.core.config.ProfileScopedStore {

    // NOTE: constants before INSTANCE - a singleton initialised above them would read them as null.

    private static final Pattern FIRST_NUMBER = Pattern.compile("([0-9]+)");
    private static final String SECTION_SIGN = String.valueOf((char) 0x00A7);

    /** Player-inventory slots to index: main + hotbar (0..35). Armor / offhand are not "storage". */
    private static final int INVENTORY_SLOTS = 36;

    /** Menu rows are 9 wide; the bottom 36 slots of any container menu are the player inventory. */
    private static final int PLAYER_PART = 36;
    private static final int COLUMNS = 9;

    private static final StorageIndex INSTANCE = new StorageIndex();

    /** Storage id -> its last seen contents. */
    private final Map<String, Snapshot> snapshots = new ConcurrentHashMap<>();

    /**
     * Storage id -> its disk form, kept up to date at capture time. Encoding here, while a level is
     * up, is what lets a write happen anywhere afterwards - a flush on world leave or at shutdown
     * has no registries left to encode with, and would otherwise write every stack without SNBT.
     */
    private final Map<String, PersistedStorage> persisted = new ConcurrentHashMap<>();

    /** Island chests get a stable number in discovery order, keyed by block position. */
    private final Map<BlockPos, Integer> chestNumbers = new ConcurrentHashMap<>();
    private final AtomicInteger nextChestNumber = new AtomicInteger(1);

    /**
     * A captured storage.
     *
     * @param stateId the menu's state id when captured, so an unchanged container is never re-copied
     */
    public record Snapshot(StorageSource source, List<ItemStack> items, long capturedAt, int stateId) {
    }

    /** One place an aggregated item was found, and how many are there. */
    public record Located(StorageSource source, int count) {
    }

    /**
     * An aggregated item across every storage.
     *
     * @param key         identity used to merge stacks (SkyBlock id when present, else the name)
     * @param displayName the name shown, colour codes stripped
     * @param icon        a representative stack, for the item render
     * @param total       total count across every location
     * @param locations   per-location breakdown, highest count first
     */
    public record Entry(String key, String displayName, ItemStack icon, int total, List<Located> locations) {
    }

    /** How the result list is ordered. */
    public enum Sort {
        AMOUNT("Amount"),
        PRICE("Price"),
        NAME("Name"),
        LOCATION("Location");

        /** The persisted name of the old "Count" sort, accepted so existing configs keep working. */
        public static Sort byName(String name) {
            if ("COUNT".equalsIgnoreCase(name)) {
                return AMOUNT;
            }
            try {
                return valueOf(name);
            } catch (IllegalArgumentException | NullPointerException bad) {
                return AMOUNT;
            }
        }

        private final String displayName;

        Sort(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }

        public Sort next() {
            Sort[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    public static StorageIndex getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.StorageSearchSettings cfg() {
        return ConfigManager.getInstance().get().storageSearch;
    }

    // ------------------------------------------------------------------
    // Capture
    // ------------------------------------------------------------------

    /**
     * Records the open container if it is a storage worth indexing.
     *
     * <p>This runs on every frame a container screen is open, so it must be nearly free in the
     * common case: it bails on the menu's {@link AbstractContainerMenu#getStateId() state id}, which
     * the server bumps whenever the container's contents actually change. A container the player is
     * merely looking at is copied <b>once</b>, not sixty times a second, while a real change is
     * still picked up on the very next frame – so the index self-updates without polling.
     */
    public void capture(AbstractContainerScreen<?> screen) {
        if (!ConfigManager.getInstance().get().skyblockMenu.previewMode.indexes()) {
            return;
        }
        loadCache();
        AbstractContainerMenu menu = screen.getMenu();
        String title = strip(screen.getTitle() == null ? "" : screen.getTitle().getString()).trim();
        StorageSource source = classify(title);
        if (source == null || !cfg().enabledFor(source.kind())) {
            return;
        }
        Snapshot existing = snapshots.get(source.id());
        if (existing != null && existing.stateId() == menu.getStateId()) {
            return; // unchanged since the last capture - nothing to do
        }
        int containerSlots = Math.max(0, menu.getItems().size() - PLAYER_PART);
        // Ender Chest pages AND Backpacks open with an options / navigation row across the top -
        // never real contents. Skip that row for both so it is not indexed or shown.
        boolean hasTopRow = source.kind() == StorageSource.Kind.ENDER_CHEST
                || source.kind() == StorageSource.Kind.BACKPACK;
        int start = hasTopRow ? Math.min(COLUMNS, containerSlots) : 0;

        // Keep every slot's POSITION, empties included, so the cached page redraws exactly like the
        // real one (items where they actually sit, not compacted into the top-left). The search
        // skips the empty placeholders, so aggregation is unaffected.
        boolean museum = source.kind() == StorageSource.Kind.MUSEUM;
        List<String> markers = museum ? new ArrayList<>() : null;
        List<ItemStack> items = new ArrayList<>(containerSlots);
        for (int i = start; i < containerSlots; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            boolean real = stack != null && !stack.isEmpty() && !isChrome(stack);
            if (real && museum && isMuseumMarker(stack)) {
                real = false;
                markers.add(strip(stack.getHoverName().getString()).trim());
            }
            items.add(real ? stack.copy() : ItemStack.EMPTY);
        }
        if (markers != null && !markers.isEmpty()) {
            // Best-guess detection: read this line after opening a museum page to check that only
            // the grey / green dye entries were skipped and no donated piece was.
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Museum] {}: skipped {} undonated/display entr(ies) {}",
                    source.displayName(), markers.size(), markers.subList(0, Math.min(4, markers.size())));
        }
        // Trailing empties carry no position information - drop them so the list stays compact.
        while (!items.isEmpty() && items.get(items.size() - 1).isEmpty()) {
            items.remove(items.size() - 1);
        }
        Snapshot snapshot = new Snapshot(source, items, System.currentTimeMillis(), menu.getStateId());
        snapshots.put(source.id(), snapshot);
        if (source.kind() != StorageSource.Kind.CHEST) {   // world-bound, position-keyed: never persisted
            persisted.put(source.id(), toPersisted(snapshot));
            saveCache();   // persist so the overview survives a restart (throttled, never dropped)
        }
    }

    // ------------------------------------------------------------------
    // Persistence – the account-wide storages survive a restart
    // ------------------------------------------------------------------

    /** A snapshot flattened for disk: only what is needed to redraw and search it. */
    private record PersistedStorage(String id, String kind, String displayName, String openCommand,
                                    long capturedAt, List<PersistedItem> items) {
    }

    /**
     * One persisted slot. {@code snbt} is the FULL stack (all components) so a reload keeps the real
     * SkyBlock texture and the real tooltip; {@code id/name/count} stay alongside as the fallback for
     * old cache files (written before snbt existed) and for stacks that failed to encode.
     */
    private record PersistedItem(String id, String name, int count, String snbt) {
    }

    /** How long to wait before another load attempt while the cache has not been read yet. */
    private static final long LOAD_RETRY_MS = 1000;

    /** True once the file was read to the end – only then do the snapshots describe the profile. */
    private volatile boolean loaded;
    /** True when the file is corrupt rather than merely unreadable right now: stop retrying. */
    private boolean unreadable;
    /** A retried load must report itself once, not once a second until it succeeds. */
    private boolean loadFailureLogged;
    private long lastLoadAttemptAt;
    /**
     * When the file may be written. A save inside the interval is DEFERRED to {@link #tick}, not
     * dropped: the old "return inside the throttle" kept only the first capture after a page was
     * opened, so every item moved in the next four seconds was lost on restart.
     */
    private final sbs.modid.client.core.config.SaveThrottle saveThrottle =
            new sbs.modid.client.core.config.SaveThrottle();

    /**
     * The one writer. Serialising two megabytes of JSON is not a render-thread job, and a single
     * thread keeps writes in submission order so an older state can never land after a newer one.
     */
    private final java.util.concurrent.ExecutorService writer =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "SBS-StorageCache-Writer");
                t.setDaemon(true);
                return t;
            });

    private StorageIndex() {
        sbs.modid.client.core.config.ProfileContext.getInstance().register(this);
        // Shutdown: queue what is pending, then wait for the writer to finish it.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            flushPending();
            writer.shutdown();
            try {
                writer.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "SBS-StorageCache-Flush"));
    }

    /**
     * Loads the cached storages, retrying until it actually succeeds. Only the account-wide families
     * are persisted (island chests are world-bound and position-keyed, so they are never written),
     * and each loaded snapshot carries {@code stateId = -1} so the next real capture replaces it.
     *
     * <p><b>A failed load must never latch.</b> Since the profile is resolved from the account's
     * remembered one, the first load attempt happens on the very first tick – which is during the
     * resource reload, before item components are bound, so rebuilding the stacks threw
     * {@code NullPointerException: Components not bound yet} straight out of the loop. The old code
     * had already set {@code loaded = true} on the way in, so that one swallowed exception killed
     * the cache for the whole session and every Ender Chest page and Backpack had to be re-opened.
     * {@code loaded} is therefore set only after a complete read, and everything transient – no file
     * yet, no level, an exception mid-rebuild – simply leaves it false to be retried.
     */
    private synchronized void loadCache() {
        if (loaded || unreadable) {
            return;
        }
        long now = System.currentTimeMillis();
        if (lastLoadAttemptAt != 0 && now - lastLoadAttemptAt < LOAD_RETRY_MS) {
            return;   // re-parsing a megabyte of SNBT every frame is not free
        }
        lastLoadAttemptAt = now;

        java.nio.file.Path path = sbs.modid.client.core.config.SBSFiles.storageCacheFile();
        if (!java.nio.file.Files.exists(path)) {
            // Absent is not an answer: this may simply be a profile that has not resolved yet, and
            // the file for the real one appears the moment it does.
            return;
        }
        // Rebuilding the stacks needs bound item components and the level's registries for the SNBT
        // round-trip. Outside a world there is nothing worth loading - placeholder icons would only
        // be thrown away by the retry, and building them this early is what threw.
        if (Minecraft.getInstance().level == null) {
            return;
        }

        List<PersistedStorage> stored;
        java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<List<PersistedStorage>>() {
        }.getType();
        try (var reader = java.nio.file.Files.newBufferedReader(path)) {
            stored = sbs.modid.client.core.config.SBSFiles.GSON.fromJson(reader, type);
        } catch (com.google.gson.JsonSyntaxException corrupt) {
            // Malformed JSON never fixes itself - retrying would just log once a second forever.
            // Deliberately NOT its sibling JsonIOException: that one wraps a read error, which is
            // exactly the transient kind that must keep retrying.
            unreadable = true;
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS] Storage cache is corrupt, ignoring it: {}",
                    corrupt.toString());
            return;
        } catch (Exception e) {
            warnOnce("[SBS] Failed to read storage cache (retrying): {}", e);
            return;   // transient (locked file, ...): try again
        }
        if (stored == null) {
            loaded = true;   // an empty file: genuinely nothing to load, and safe to overwrite
            return;
        }

        try {
            int decodeFailures = 0;
            int decodable = 0;
            for (PersistedStorage ps : stored) {
                StorageSource.Kind kind;
                try {
                    kind = StorageSource.Kind.valueOf(ps.kind());
                } catch (IllegalArgumentException bad) {
                    continue;
                }
                // Entries captured under the old substring rule from a menu that is not storage (an AH
                // search for "backpack" became Backpack 1): drop them, one line each. The next save
                // writes the cache without them. Real storages pass both checks untouched.
                if (isBogusEntry(ps, kind)) {
                    SkyblockSimplifiedSBS.LOGGER.info("[SBS][Storage] dropped cached '{}' ({}): it was "
                            + "captured from a menu that is not storage", ps.displayName(), ps.id());
                    continue;
                }
                // Caches written before museum markers were filtered still hold the grey / green
                // dyes. A marker carries no SkyBlock id, so drop it on the way in instead of making
                // the player re-open the museum for the search to stop counting it.
                boolean museum = kind == StorageSource.Kind.MUSEUM;
                List<ItemStack> items = new ArrayList<>();
                for (PersistedItem pi : ps.items()) {
                    if (pi.count() <= 0 || (museum && (pi.id() == null || pi.id().isEmpty()))) {
                        items.add(ItemStack.EMPTY);   // a position-preserving empty slot
                        continue;
                    }
                    boolean hasSnbt = pi.snbt() != null && !pi.snbt().isEmpty();
                    ItemStack stack = decodeStack(pi.snbt());
                    if (hasSnbt) {
                        decodable++;
                    }
                    if (stack == null) {
                        // No snbt (a cache written before it existed) or a stack that would not
                        // decode: the catalogue icon is the fallback, id/name/count still search.
                        if (hasSnbt) {
                            decodeFailures++;
                        }
                        stack = sbs.modid.client.economy.recipe.logic.SkyBlockItemIcons.getInstance()
                                .icon(pi.id(), null, Math.max(1, pi.count()));
                    }
                    items.add(stack);
                }
                StorageSource source = new StorageSource(ps.id(), ps.displayName(), kind,
                        ps.openCommand() == null || ps.openCommand().isBlank() ? null : ps.openCommand());
                snapshots.put(ps.id(), new Snapshot(source, items, ps.capturedAt(), -1));
                if (kind != StorageSource.Kind.CHEST) {
                    persisted.put(ps.id(), ps);   // written back as read until it is captured again
                }
            }
            loaded = true;
            if (decodeFailures > 0) {
                // All of them failing looks exactly like "nothing was ever saved" from the UI, so
                // say so rather than leaving the player to guess.
                SkyblockSimplifiedSBS.LOGGER.warn("[SBS] Storage cache: {} of {} stack(s) did not decode.",
                        decodeFailures, decodable);
            }
            SkyblockSimplifiedSBS.LOGGER.info("[SBS] Storage cache loaded: {} storage(s) for profile {}.",
                    snapshots.size(), sbs.modid.client.core.config.ProfileContext.getInstance().profile());
        } catch (Exception e) {
            // Whatever went wrong mid-rebuild, it is not a reason to spend the session pretending
            // the player owns nothing: leave loaded false so the next read tries again.
            warnOnce("[SBS] Failed to rebuild the storage cache (retrying): {}", e);
        }
    }

    /** Reports a load failure the first time only – the retry would otherwise log once a second. */
    private void warnOnce(String message, Exception e) {
        if (loadFailureLogged) {
            return;
        }
        loadFailureLogged = true;
        SkyblockSimplifiedSBS.LOGGER.warn(message, e.toString());
    }

    /**
     * Decodes a persisted stack back to its full self ({@code null} on failure). The SNBT round-trip
     * goes through the level's registry context because item components reference registries.
     */
    private static ItemStack decodeStack(String snbt) {
        if (snbt == null || snbt.isEmpty()) {
            return null;
        }
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level == null) {
                return null;
            }
            CompoundTag tag = net.minecraft.nbt.TagParser.parseCompoundFully(snbt);
            var ops = minecraft.level.registryAccess()
                    .createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE);
            ItemStack stack = ItemStack.OPTIONAL_CODEC.parse(ops, tag).result().orElse(ItemStack.EMPTY);
            return stack.isEmpty() ? null : stack;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Encodes the full stack (all components) as SNBT, or {@code ""} when no level is up / it fails. */
    private static String encodeStack(ItemStack stack) {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level == null) {
                return "";
            }
            var ops = minecraft.level.registryAccess()
                    .createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE);
            return ItemStack.OPTIONAL_CODEC.encodeStart(ops, stack).result()
                    .map(Object::toString).orElse("");
        } catch (Throwable t) {
            return "";
        }
    }

    /** Save the current in-memory storages to the profile file immediately (on a profile switch). */
    @Override
    public void flushProfile() {
        flushPending();
    }

    /** Drop the cached storages and reload them from the (now current) profile's file. */
    @Override
    public void reloadProfile() {
        synchronized (this) {
            snapshots.clear();
            persisted.clear();
            loaded = false;
            unreadable = false;       // a different profile, a different file
            loadFailureLogged = false;
            lastLoadAttemptAt = 0;    // load it now, do not sit out the retry throttle
        }
        loadCache();
    }

    /** A change to persist: written now, or at most one save interval later by {@link #tick}. */
    private void saveCache() {
        if (saveThrottle.request(false, System.currentTimeMillis())) {
            writeCache();
        }
    }

    /** Client tick: writes a change the throttle deferred once its interval has passed. */
    public void tick() {
        if (saveThrottle.pending(System.currentTimeMillis())) {
            writeCache();
        }
    }

    /**
     * Writes a pending change now, ignoring the interval - for the moments nothing will write
     * later: a screen closing, a profile switch, a world leave, shutdown. No change, no write.
     */
    public void flushPending() {
        if (saveThrottle.dirty()) {
            writeCache();
        }
    }

    /**
     * Whether the cache file may be overwritten right now.
     *
     * <p>Two ways to destroy the player's storages with a write, both seen: overwriting a file we
     * never managed to read (the session's snapshots are then only what was re-opened, so a failed
     * load quietly truncated a full cache down to the one page that was looked at), and writing
     * under the {@code default} profile before the real one is known (the data is stranded in a
     * folder nothing reads again). Neither is recoverable, so both simply skip the write - the
     * capture stays in memory and is written by the next save once the state is sound.
     */
    private boolean canWrite() {
        if (!sbs.modid.client.core.config.ProfileContext.getInstance().known()) {
            return false;
        }
        return loaded || !java.nio.file.Files.exists(sbs.modid.client.core.config.SBSFiles.storageCacheFile());
    }

    /**
     * Queues a write of the account-wide storages to the current profile file (no throttle). A
     * refused write leaves the throttle dirty, so the change is retried once the state is sound.
     */
    private synchronized void writeCache() {
        if (!canWrite()) {
            return;
        }
        // Resolved NOW: a profile switch flushes to the old folder and swaps right after.
        java.nio.file.Path path = sbs.modid.client.core.config.SBSFiles.storageCacheFile();
        List<PersistedStorage> out = new ArrayList<>(persisted.values());
        saveThrottle.written(System.currentTimeMillis());
        try {
            writer.execute(() -> writeFile(path, out));
        } catch (java.util.concurrent.RejectedExecutionException shuttingDown) {
            writeFile(path, out);
        }
    }

    private static void writeFile(java.nio.file.Path path, List<PersistedStorage> out) {
        try {
            sbs.modid.client.core.config.SBSFiles.ensureParent(path);
            try (var writer = java.nio.file.Files.newBufferedWriter(path)) {
                sbs.modid.client.core.config.SBSFiles.GSON.toJson(out, writer);
            }
            sbs.modid.client.core.perf.Perf.countDiskWrite();
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS] Failed to write storage cache: {}", e.toString());
        }
    }

    /** A snapshot's disk form. Needs a level (the SNBT encode), so it runs at capture time. */
    private static PersistedStorage toPersisted(Snapshot snapshot) {
        List<PersistedItem> items = new ArrayList<>(snapshot.items().size());
        for (ItemStack stack : snapshot.items()) {
            if (stack == null || stack.isEmpty()) {
                items.add(new PersistedItem("", "", 0, ""));  // empty slot: keeps the layout on reload
                continue;
            }
            CompoundTag extra = SkyblockItem.extraAttributes(stack);
            String id = extra.getStringOr("id", "");
            items.add(new PersistedItem(id, strip(stack.getHoverName().getString()),
                    stack.getCount(), encodeStack(stack)));
        }
        return new PersistedStorage(snapshot.source().id(), snapshot.source().kind().name(),
                snapshot.source().displayName(), snapshot.source().openCommand(),
                snapshot.capturedAt(), items);
    }

    /**
     * Whether a menu title names a sack. <b>The</b> sack check - the Sack Overlay asks this rather
     * than matching the title a second time, so the two can never disagree about what a sack is.
     *
     * <p>Title-based because that is the only handle Hypixel gives. Note this also matches the Sack
     * of Sacks, which is a menu <i>of sacks</i> rather than of items: right for storage indexing,
     * and the overlay excludes it separately.
     */
    public static boolean isSackTitle(String title) {
        // The whole title, never a substring: "Auctions: \"sack\"" is an AH search, not a sack.
        return StorageTitles.isSackTitle(title);
    }

    /**
     * Maps a menu title to the storage it represents, or {@code null} when it is not storage at all
     * (the Bazaar, an NPC menu, ...). Title-based because that is the only handle Hypixel gives.
     */
    private StorageSource classify(String title) {
        // Whole-title rules, see StorageTitles: a substring match saved an AH search for "backpack"
        // as Backpack 1. Ids are unchanged, so every real entry already on disk stays valid.
        StorageTitles.Match match = StorageTitles.classify(title);
        if (match == null) {
            return null;
        }
        String lower = title.toLowerCase(Locale.ROOT);
        return switch (match.kind()) {
            case ENDER_CHEST -> new StorageSource("ender_chest:" + match.number(), "Ender Chest " + match.number(),
                    StorageSource.Kind.ENDER_CHEST, "enderchest " + match.number());
            case BACKPACK -> new StorageSource("backpack:" + match.number(), "Backpack " + match.number(),
                    StorageSource.Kind.BACKPACK, "backpack " + match.number());
            // "Mining Sack", "Farming Sack", ... - keep Hypixel's own name, it is the location.
            case SACK -> new StorageSource("sack:" + lower, title, StorageSource.Kind.SACKS, "sacks");
            case MUSEUM -> new StorageSource("museum:" + lower, title, StorageSource.Kind.MUSEUM, "museum");
            case VAULT -> new StorageSource("vault", "Personal Vault", StorageSource.Kind.VAULT, null);
            case CHEST -> islandChest();
        };
    }

    /**
     * Whether a persisted entry came from a non-storage title. Sack and museum ids carry their title
     * and are re-checked against {@link StorageTitles}; backpacks and Ender Chest pages carry only a
     * number, so a page whose stacks are mostly Auction House listings is the tell.
     */
    private static boolean isBogusEntry(PersistedStorage ps, StorageSource.Kind kind) {
        if (!StorageTitles.idStillValid(ps.id(), ps.kind())) {
            return true;
        }
        if ((kind == StorageSource.Kind.BACKPACK || kind == StorageSource.Kind.ENDER_CHEST) && ps.items() != null) {
            List<String> snbts = new ArrayList<>(ps.items().size());
            for (PersistedItem item : ps.items()) {
                snbts.add(item.count() <= 0 ? null : item.snbt());
            }
            return StorageTitles.looksLikeAuctionListing(snbts);
        }
        return false;
    }

    /**
     * The island chest the player just opened, numbered stably by its block position so "Chest 12"
     * means the same chest for the whole session. Falls back to a single unnumbered entry when the
     * position is unknown (the interaction was missed), which is better than inventing a number.
     */
    private StorageSource islandChest() {
        BlockPos pos = BlockInteractTracker.recentBlock();
        if (pos == null) {
            return new StorageSource("chest:unknown", "Chest", StorageSource.Kind.CHEST, null);
        }
        int number = chestNumbers.computeIfAbsent(pos.immutable(), p -> nextChestNumber.getAndIncrement());
        return new StorageSource("chest:" + pos.getX() + "," + pos.getY() + "," + pos.getZ(),
                "Chest " + number, StorageSource.Kind.CHEST, null);
    }

    /** Drops everything – the full manual reset. Not called automatically; see {@link #onWorldChange}. */
    public void clear() {
        snapshots.clear();
        persisted.clear();
        chestNumbers.clear();
        nextChestNumber.set(1);
    }

    /**
     * Called when the player leaves a world (lobby switch, disconnect). Drops only the storages
     * bound to <b>that</b> world – the island chests, whose {@link BlockPos} keys and numbering
     * would otherwise collide with containers of the next world ("Chest 12" from your island
     * showing stale contents inside a dungeon). The account-wide storages – Ender Chest, Backpacks,
     * Sacks, Vault, Museum – survive every Hypixel transfer, so wiping them here would just throw
     * away valid snapshots on each of the many lobby switches a session has.
     */
    public void onWorldChange() {
        flushPending();
        snapshots.values().removeIf(s -> s.source().kind() == StorageSource.Kind.CHEST);
        chestNumbers.clear();
        nextChestNumber.set(1);
    }

    /** How many storages are currently indexed (the player inventory is live and not counted). */
    public int sourceCount() {
        return snapshots.size();
    }

    /** The most recent snapshot of a given storage, or {@code null}. */
    public Snapshot snapshot(String id) {
        return snapshots.get(id);
    }

    /**
     * Every snapshot of the given kinds, ordered kind-first and then by the storage's own number –
     * so "Ender Chest 1..9" precede "Backpack 1..n" in natural order. This is what the storage
     * overview renders.
     */
    public List<Snapshot> snapshotsOf(StorageSource.Kind... kinds) {
        loadCache();
        List<StorageSource.Kind> order = List.of(kinds);
        List<Snapshot> out = new ArrayList<>();
        for (Snapshot snapshot : snapshots.values()) {
            if (order.contains(snapshot.source().kind())) {
                out.add(snapshot);
            }
        }
        out.sort((a, b) -> {
            int kind = Integer.compare(order.indexOf(a.source().kind()), order.indexOf(b.source().kind()));
            if (kind != 0) {
                return kind;
            }
            return Integer.compare(firstNumber(a.source().id(), 0), firstNumber(b.source().id(), 0));
        });
        return out;
    }

    // ------------------------------------------------------------------
    // Query
    // ------------------------------------------------------------------

    /**
     * Every stored item matching {@code query}, merged across storages and ordered by {@code sort}.
     * An empty query returns everything.
     */
    public List<Entry> search(String query, Sort sort) {
        loadCache();
        String needle = query == null ? "" : strip(query).trim().toLowerCase(Locale.ROOT);
        Map<String, Aggregate> merged = new LinkedHashMap<>();

        if (cfg().enabledFor(StorageSource.Kind.INVENTORY)) {
            collect(inventorySource(), livePlayerItems(), needle, merged);
        }
        for (Snapshot snapshot : snapshots.values()) {
            if (cfg().enabledFor(snapshot.source().kind())) {
                collect(snapshot.source(), snapshot.items(), needle, merged);
            }
        }

        List<Entry> out = new ArrayList<>(merged.size());
        for (Aggregate aggregate : merged.values()) {
            out.add(aggregate.toEntry());
        }
        sort(out, sort);
        return out;
    }

    /** The live player inventory – never snapshotted, so it is always current. */
    private static List<ItemStack> livePlayerItems() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return List.of();
        }
        Inventory inventory = minecraft.player.getInventory();
        List<ItemStack> items = new ArrayList<>(INVENTORY_SLOTS);
        for (int i = 0; i < INVENTORY_SLOTS && i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack != null && !stack.isEmpty()) {
                items.add(stack);
            }
        }
        return items;
    }

    private static StorageSource inventorySource() {
        return new StorageSource("inventory", "Inventory", StorageSource.Kind.INVENTORY, null);
    }

    /** Folds one storage's stacks into the aggregate map, honouring the query. */
    private void collect(StorageSource source, List<ItemStack> items, String needle,
                         Map<String, Aggregate> merged) {
        boolean searchLore = cfg().searchLore;
        for (ItemStack stack : items) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            String name = strip(stack.getHoverName().getString()).trim();
            String key = identityOf(stack, name);
            if (!needle.isEmpty() && !matches(stack, name, key, needle, searchLore)) {
                continue;
            }
            merged.computeIfAbsent(key, k -> new Aggregate(k, name, stack))
                    .add(source, stack.getCount());
        }
    }

    /**
     * Merge identity: the SkyBlock id when the stack has one (so "Enchanted Bread" from a sack and
     * from a chest are the same row even if their display names differ), else the plain name.
     */
    private static String identityOf(ItemStack stack, String name) {
        CompoundTag extra = SkyblockItem.extraAttributes(stack);
        String id = extra.getStringOr("id", "");
        return id.isEmpty() ? name.toLowerCase(Locale.ROOT) : id;
    }

    private static boolean matches(ItemStack stack, String name, String key, String needle,
                                   boolean searchLore) {
        if (name.toLowerCase(Locale.ROOT).contains(needle) || key.toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        if (!searchLore) {
            return false;
        }
        var lore = stack.get(net.minecraft.core.component.DataComponents.LORE);
        if (lore == null) {
            return false;
        }
        for (var line : lore.lines()) {
            if (strip(line.getString()).toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static void sort(List<Entry> entries, Sort sort) {
        switch (sort) {
            case AMOUNT -> entries.sort((a, b) -> Integer.compare(b.total(), a.total()));
            case PRICE -> entries.sort((a, b) -> Double.compare(totalValue(b), totalValue(a)));
            case LOCATION -> entries.sort((a, b) -> {
                String sa = a.locations().isEmpty() ? "" : a.locations().get(0).source().displayName();
                String sb = b.locations().isEmpty() ? "" : b.locations().get(0).source().displayName();
                int cmp = sa.compareToIgnoreCase(sb);
                return cmp != 0 ? cmp : a.displayName().compareToIgnoreCase(b.displayName());
            });
            default -> entries.sort((a, b) -> a.displayName().compareToIgnoreCase(b.displayName()));
        }
    }

    /** An entry's stack value: unit buy price (Bazaar, else lowest BIN) x total, 0 when unpriced. */
    public static double totalValue(Entry entry) {
        Long unit = sbs.modid.client.economy.recipe.logic.PriceEstimator.getInstance().buyPrice(entry.key());
        return unit == null ? 0 : unit * (double) entry.total();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Mutable accumulator behind one {@link Entry}. */
    private static final class Aggregate {
        private final String key;
        private final String displayName;
        private final ItemStack icon;
        private final Map<String, Located> byLocation = new LinkedHashMap<>();
        private int total;

        private Aggregate(String key, String displayName, ItemStack icon) {
            this.key = key;
            this.displayName = displayName;
            this.icon = icon;
        }

        private void add(StorageSource source, int count) {
            total += count;
            byLocation.merge(source.id(), new Located(source, count),
                    (a, b) -> new Located(a.source(), a.count() + b.count()));
        }

        private Entry toEntry() {
            List<Located> locations = new ArrayList<>(byLocation.values());
            locations.sort((a, b) -> Integer.compare(b.count(), a.count()));
            return new Entry(key, displayName, icon, total, locations);
        }
    }

    private static int firstNumber(String text, int fallback) {
        Matcher matcher = FIRST_NUMBER.matcher(text);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : fallback;
    }

    static String strip(String text) {
        return text.replaceAll(SECTION_SIGN + ".", "");
    }

    /** Menu chrome that is decoration, not stored items: filler panes and navigation buttons. */
    private static boolean isChrome(ItemStack stack) {
        String path = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(stack.getItem()).getPath();
        if (path.contains("glass_pane")) {
            return true;
        }
        String name = strip(stack.getHoverName().getString()).trim().toLowerCase(Locale.ROOT);
        return name.isEmpty() || name.equals("go back") || name.equals("close")
                || name.startsWith("next page") || name.startsWith("previous page");
    }

    /**
     * A museum slot that is a state marker rather than a stored item. The museum lists every
     * donatable entry, not just the ones you own: an entry you have <b>not</b> donated is drawn as a
     * grey dye carrying that item's name, and a donated entry the museum cannot show as an item
     * (armour sets) as a green dye. Indexing either tells the player they own something they do not,
     * so both are dropped – a really donated piece is the item's own stack.
     *
     * <p>Two shapes are recognised: a stack with no SkyBlock item data at all (every donated piece
     * carries its {@code ExtraAttributes} id), and an item name painted onto a dye base. Hypixel's
     * own dyes – "Pure White Dye" and friends – are named as dyes and therefore stay.
     */
    private static boolean isMuseumMarker(ItemStack stack) {
        if (SkyblockItem.id(stack) == null) {
            return true;
        }
        String path = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(stack.getItem()).getPath();
        return path.endsWith("_dye")
                && !strip(stack.getHoverName().getString()).toLowerCase(Locale.ROOT).contains("dye");
    }
}
