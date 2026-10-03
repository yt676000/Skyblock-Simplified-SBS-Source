/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.collection;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.item.SkyblockItem;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects which collection is being worked on and keeps its exact counter live – a universal
 * farming counter.
 *
 * <p><b>Anchor + count-along.</b> The tracked value = an authoritative <b>anchor</b> (the exact total)
 * plus items <b>counted</b> since it:
 * <ul>
 *   <li>Anchors: the tab-list <i>Collection</i> widget AND the <b>Collections menu</b> (opening it
 *       re-reads the exact "Total Collected"). Anchors are <b>monotonic</b> – a fresher (higher)
 *       reading raises the total; a stale (lower) one is ignored, so the widget and the menu never
 *       fight each other down.</li>
 *   <li>Count-along: the "[Sacks] +N Item" chat breakdown (the real picked-up amounts).</li>
 * </ul>
 *
 * <p>Every lookup is by a NORMALIZED key ({@link CollectionCatalog#match}), so the count-along works
 * even when the dropped item's name differs from the collection name ("Hardstone" ↔ "Hard Stone" ↔
 * {@code HARD_STONE}) – the bug that made hardstone/redstone/… under-count and then jump when the menu
 * corrected them. Tracks are keyed by the collection id.
 */
public final class CollectionTracker {

    private static final CollectionTracker INSTANCE = new CollectionTracker();

    /** "<Name>: 1,234,567" – the tab Collection widget line (optional leading symbol/icon). */
    private static final Pattern WIDGET_LINE =
            Pattern.compile("^\\s*[^A-Za-z0-9]*([A-Za-z][A-Za-z' -]{1,30}?):\\s*([\\d,]{1,20})\\s*$");
    /** One "[Sacks]" hover line: "+24 Cobblestone (Mining Sack)". */
    private static final Pattern SACK_LINE = Pattern.compile("([+-][\\d,.]+) (.+?) \\((.+)\\)");
    /** The Collections menu "Total Collected: 1,234,567" lore line. */
    private static final Pattern COLLECTED_LINE =
            Pattern.compile("(?i)(?:total )?collected:?\\s*([\\d,]+)");
    /** A trailing roman-numeral tier on a menu item name ("Wheat III" -> "Wheat"). */
    private static final Pattern TRAILING_TIER = Pattern.compile("\\s+[IVXLC]+$");

    private static final char SECTION_SIGN = (char) 0x00A7;
    private static final long SCAN_INTERVAL_MS = 500;

    /** Per-collection state. displayValue = anchorValue + gain; the anchor is monotonic. */
    private static final class Track {
        private final CollectionCatalog.Info info;
        private long anchorValue;      // highest authoritative value (widget or menu)
        private boolean anchored;
        private long gain;             // items counted since the anchor was last raised
        private long sessionGained;    // all counted gains this session (for the "+N" display)
        private long firstActivityAt;
        private long lastActivityAt;

        Track(CollectionCatalog.Info info) {
            this.info = info;
        }

        long displayValue() {
            return anchorValue + gain;
        }
    }

    private final Map<String, Track> tracks = new HashMap<>();

    private volatile String activeId = "";
    private long lastScanAt;
    private long lastDebugAt;
    private long lastMenuLogAt;

    private CollectionTracker() {
    }

    public static CollectionTracker getInstance() {
        return INSTANCE;
    }

    private static sbs.modid.client.core.config.SBSConfig.CollectionTrackerSettings cfg() {
        return ConfigManager.getInstance().get().collectionTracker;
    }

    // ------------------------------------------------------------------ per-tick anchors

    /** Called every client tick (throttled). Reads the tab widget AND the open Collections menu. */
    public void onClientTick() {
        if (!cfg().enabled) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastScanAt < SCAN_INTERVAL_MS) {
            return;
        }
        lastScanAt = now;

        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener connection = minecraft.getConnection();
        if (connection == null || minecraft.player == null) {
            tracks.clear();
            activeId = "";
            return;
        }
        scanWidget(connection, now);
        scanCollectionMenu(now);
        diagnostic(now);
    }

    /** The tab Collection widget: a fake-player line "Wheat: 1,234,567". */
    private void scanWidget(ClientPacketListener connection, long now) {
        for (PlayerInfo info : connection.getOnlinePlayers()) {
            Component display = info.getTabListDisplayName();
            if (display == null) {
                continue; // real players carry no styled tab name; widget lines do
            }
            Matcher matcher = WIDGET_LINE.matcher(strip(display.getString()));
            if (!matcher.matches()) {
                continue;
            }
            CollectionCatalog.Info collection = CollectionCatalog.match(matcher.group(1).trim());
            if (collection == null) {
                continue; // "Purse: 1,234" and friends - not a collection
            }
            // A widget increase is a real gain, so it may make this the active collection.
            applyAnchor(collection, parseCount(matcher.group(2)), now, true);
        }
    }

    /**
     * The Collections menu fallback: while a container titled "… Collection …" is open, re-read each
     * item's "Total Collected" as an exact anchor (matched by the item's NBT id first, then its name).
     */
    private void scanCollectionMenu(long now) {
        Screen screen = GuiStateManager.getInstance().getCurrentScreen();
        if (!(screen instanceof AbstractContainerScreen<?> container)) {
            return;
        }
        String title = strip(screen.getTitle() != null ? screen.getTitle().getString() : "");
        if (!title.toLowerCase(Locale.ROOT).contains("collection")) {
            return;
        }
        AbstractContainerMenu menu = container.getMenu();
        int matched = 0;
        String sample = null;
        for (int i = 0; i < menu.slots.size(); i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            CollectionCatalog.Info collection = collectionOf(stack);
            if (collection == null) {
                continue;
            }
            long total = collectedInLore(stack);
            if (sample == null) {
                sample = collection.name() + " -> " + total;
            }
            if (total >= 0) {
                // The menu only syncs the number; it must not steal "active" from what is being farmed.
                applyAnchor(collection, total, now, false);
                matched++;
            }
        }
        if (matched == 0 && now - lastMenuLogAt > 5_000L) {
            lastMenuLogAt = now;
            SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][Collection] menu '{}' open, no totals parsed (sample: {})", title, sample);
        }
    }

    /** A menu item -> its collection: try the NBT SkyBlock id, then the (tier-stripped) display name. */
    private static CollectionCatalog.Info collectionOf(ItemStack stack) {
        String id = SkyblockItem.id(stack);
        CollectionCatalog.Info info = id == null ? null : CollectionCatalog.match(id);
        if (info != null) {
            return info;
        }
        return CollectionCatalog.match(cleanCollectionName(strip(stack.getHoverName().getString())));
    }

    /**
     * Applies an authoritative anchor MONOTONICALLY: a higher value raises the total (any count-along
     * lead is kept), a lower/equal value is ignored – so a stale widget and a fresh menu never fight
     * the number down, and it never decreases. {@code fromGain} lets a widget increase mark the
     * collection active; the menu never does.
     */
    private void applyAnchor(CollectionCatalog.Info collection, long value, long now, boolean fromGain) {
        Track track = tracks.computeIfAbsent(collection.id(), k -> new Track(collection));
        if (!track.anchored) {
            track.anchored = true;
            track.anchorValue = value;
            track.gain = 0;
            return;
        }
        if (value <= track.anchorValue) {
            return; // stale / lagging reading - ignore, keep the live count-along value
        }
        long disp = track.displayValue();
        track.anchorValue = value;
        track.gain = Math.max(0, disp - value); // keep any count-along lead; never lower the total
        if (fromGain) {
            markActivity(track, now); // a widget increase = actively gaining this collection
        }
    }

    // ------------------------------------------------------------------ count-along (chat sacks)

    /** Fed every chat line from {@code ChatPriceListenerMixin}; counts collection gains from sacks. */
    public void onChat(String text, Component component) {
        if (!cfg().enabled || text == null || !text.startsWith("[Sacks]") || component == null) {
            return;
        }
        StringBuilder hover = new StringBuilder();
        collectHoverText(component, hover);
        long now = System.currentTimeMillis();
        // ONE amount per (item, sack) per message, collapsed by MAX: the hover can sit on several
        // styled segments of the chat line, so the same breakdown may be collected twice.
        Map<String, Long> perItem = new HashMap<>();
        for (String line : hover.toString().split("\n")) {
            Matcher matcher = SACK_LINE.matcher(strip(line));
            if (!matcher.find() || matcher.group(1).startsWith("-")) {
                continue; // a "-N" removal is spending items out of the sack, not a gain
            }
            CollectionCatalog.Info collection = CollectionCatalog.match(matcher.group(2).trim());
            if (collection == null) {
                continue;
            }
            long amount = parseCount(matcher.group(1));
            if (amount > 0) {
                perItem.merge(collection.id() + "|" + matcher.group(3), amount, Math::max);
            }
        }
        for (Map.Entry<String, Long> entry : perItem.entrySet()) {
            String id = entry.getKey().substring(0, entry.getKey().indexOf('|'));
            CollectionCatalog.Info collection = CollectionCatalog.match(id);
            if (collection != null) {
                addGain(collection, entry.getValue(), now);
            }
        }
    }

    private void addGain(CollectionCatalog.Info collection, long amount, long now) {
        Track track = tracks.computeIfAbsent(collection.id(), k -> new Track(collection));
        track.gain += amount;
        track.sessionGained += amount;
        markActivity(track, now);
    }

    private void markActivity(Track track, long now) {
        if (track.firstActivityAt == 0) {
            track.firstActivityAt = now;
        }
        track.lastActivityAt = now;
        activeId = track.info.id();
    }

    // ------------------------------------------------------------------ HUD read model

    /**
     * Snapshot for the HUD. When a collection is pinned ({@code cfg().pinnedCollection}) that one is
     * always shown – even with no data yet ({@code anchored=false}, so the HUD prompts to open the
     * menu). Otherwise the auto-detected active collection is shown, or {@code null} when there is
     * none.
     */
    public Snapshot snapshot() {
        CollectionCatalog.Info pinned = pinnedInfo();
        if (pinned != null) {
            Track track = tracks.get(pinned.id());
            if (track != null && (track.anchored || track.gain > 0)) {
                return toSnapshot(track);
            }
            return new Snapshot(pinned, 0, 0, 0, 0, false); // pinned but not loaded yet
        }
        Track track = tracks.get(activeId);
        if (track == null || (!track.anchored && track.gain == 0)) {
            return null;
        }
        return toSnapshot(track);
    }

    /**
     * Every collection's session gain so far, by collection id - a copy, read on the client thread.
     * The Farming Session Summary subtracts two of these; it never counts crops itself.
     */
    public Map<String, Long> sessionGains() {
        Map<String, Long> out = new HashMap<>();
        for (Track track : tracks.values()) {
            if (track.sessionGained > 0) {
                out.put(track.info.id(), track.sessionGained);
            }
        }
        return out;
    }

    /** The pinned collection, or {@code null} when none is set (auto-detect). */
    private static CollectionCatalog.Info pinnedInfo() {
        String pinned = cfg().pinnedCollection;
        return pinned == null || pinned.isBlank() ? null : CollectionCatalog.match(pinned);
    }

    private static Snapshot toSnapshot(Track track) {
        long sessionGain = track.sessionGained;
        double perHour = 0;
        if (track.firstActivityAt > 0 && track.lastActivityAt > track.firstActivityAt && sessionGain > 0) {
            perHour = sessionGain * 3_600_000.0 / (track.lastActivityAt - track.firstActivityAt);
        }
        return new Snapshot(track.info, track.displayValue(), sessionGain, perHour,
                track.lastActivityAt, track.anchored);
    }

    public record Snapshot(CollectionCatalog.Info info, long current, long sessionGain,
                           double perHour, long lastGainAt, boolean anchored) {
    }

    // ------------------------------------------------------------------ helpers

    /** Item display name → catalogue name: drop a trailing tier and a " Collection" suffix. */
    private static String cleanCollectionName(String raw) {
        String name = raw.trim();
        if (name.toLowerCase(Locale.ROOT).endsWith(" collection")) {
            name = name.substring(0, name.length() - " collection".length()).trim();
        }
        return TRAILING_TIER.matcher(name).replaceAll("").trim();
    }

    /** The "Total Collected" number in an item's lore, or {@code -1} when the line is absent. */
    private static long collectedInLore(ItemStack stack) {
        Object component = stack.get(DataComponents.LORE);
        if (!(component instanceof ItemLore lore)) {
            return -1;
        }
        for (Object line : lore.lines()) {
            if (!(line instanceof Component text)) {
                continue;
            }
            Matcher matcher = COLLECTED_LINE.matcher(strip(text.getString()));
            if (matcher.find()) {
                return parseCount(matcher.group(1));
            }
        }
        return -1;
    }

    private static long parseCount(String raw) {
        try {
            return Long.parseLong(raw.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Every hover ShowText in the component tree, one line each (FishingTracker idiom). */
    private static void collectHoverText(Component component, StringBuilder out) {
        if (component.getStyle().getHoverEvent() instanceof HoverEvent.ShowText(Component text)) {
            out.append(text.getString()).append('\n');
        }
        for (Component sibling : component.getSiblings()) {
            collectHoverText(sibling, out);
        }
    }

    private void diagnostic(long now) {
        if (now - lastDebugAt < 30_000L || tracks.isEmpty()) {
            return;
        }
        lastDebugAt = now;
        Track active = tracks.get(activeId);
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Collection] tracked={} active={} value={} session=+{} catalogue={}",
                tracks.keySet(), activeId, active == null ? 0 : active.displayValue(),
                active == null ? 0 : active.sessionGained, CollectionCatalog.isLoaded());
    }

    private static String strip(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == SECTION_SIGN && i + 1 < text.length()) {
                i++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
