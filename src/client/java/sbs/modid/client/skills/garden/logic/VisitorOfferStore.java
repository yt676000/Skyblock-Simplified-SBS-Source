/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.garden.logic;

import com.google.gson.JsonSyntaxException;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.ProfileContext;
import sbs.modid.client.core.config.ProfileScopedStore;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.core.tab.TabWidgets;
import sbs.modid.client.skills.garden.logic.VisitorMenu.Required;
import sbs.modid.client.skills.garden.logic.VisitorShoppingList.Offer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every waiting visitor's offer that has been seen, per account and SkyBlock profile, keyed by the
 * visitor's name - what they want is only knowable once their menu has been opened, and it stays
 * true until they are served or refused.
 *
 * <p>Fed three ways, all read-only: the visitor menu when it is open (the offer), the tab list's
 * {@code Visitors:} widget (who is still waiting - a visitor who left it is dropped), and the accept
 * chat line (dropped at once). Nothing is ever guessed: a visitor in the tab whose menu was never
 * opened has no entry, and the list says so.
 *
 * <p><b>The visitor's name is the menu title</b> - {@code ESTIMATED}, no visitor menu has been
 * probed. Logged once per visit under {@code [SBS][Visitor]} so the first visit shows it.
 */
public final class VisitorOfferStore implements ProfileScopedStore {

    private static final VisitorOfferStore INSTANCE = new VisitorOfferStore();

    private static final String FILE = "visitor-offers.json";
    private static final int SCHEMA_VERSION = 1;
    private static final long TAB_CHECK_MS = 1000;

    /** The file, as Gson sees it. Field names are the file format. */
    static final class Data {
        int schemaVersion = SCHEMA_VERSION;
        Map<String, Saved> offers = new LinkedHashMap<>();
    }

    static final class Saved {
        String visitor;
        List<String> names = new ArrayList<>();
        List<Integer> amounts = new ArrayList<>();
        int copper = -1;
        String rareReward;
        long seenAt;
    }

    private Data data = new Data();
    private boolean loaded;
    private boolean unreadable;
    private volatile int generation;

    private AbstractContainerScreen<?> lastScreen;
    private int lastState = -1;
    private long lastTabCheckAt;
    /** The last tab read; {@code null} = the widget was not visible. */
    private volatile List<String> waiting;

    private VisitorOfferStore() {
        ProfileContext.getInstance().register(this);
    }

    public static VisitorOfferStore getInstance() {
        return INSTANCE;
    }

    private static boolean enabled() {
        return ConfigManager.getInstance().get().gardenHelpers.visitorShoppingList;
    }

    /** Bumped on every change, for anything caching a derived view. */
    public int generation() {
        return generation;
    }

    // ------------------------------------------------------------------ feeding

    /** Client tick: reads an open visitor menu on change, and the tab widget once a second. */
    public void tick() {
        if (!enabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastTabCheckAt >= TAB_CHECK_MS) {
            lastTabCheckAt = now;
            List<String> read = VisitorShoppingList.waitingVisitors(TabWidgets.lines());
            if (read != null) {
                waiting = List.copyOf(read);
                pruneTo(read);
            }
        }
        if (!(GuiStateManager.getInstance().getCurrentScreen() instanceof AbstractContainerScreen<?> screen)) {
            lastScreen = null;
            return;
        }
        int state = screen.getMenu().getStateId();
        if (screen == lastScreen && state == lastState) {
            return;
        }
        lastScreen = screen;
        lastState = state;
        if (!VisitorMenu.isVisitorMenu(screen.getMenu())) {
            return;
        }
        List<Required> items = VisitorMenu.requiredItems(screen.getMenu());
        if (items.isEmpty()) {
            return;   // VisitorBazaarButtons already logs a visitor menu it could not read
        }
        List<String> lore = VisitorMenu.offerLore(screen.getMenu());
        String visitor = resolveName(VisitorMenu.strip(screen.getTitle().getString()).trim());
        record(new Offer(visitor, items, VisitorMenu.parseCopper(lore), VisitorGuard.valuableRewardIn(lore)));
    }

    /**
     * The tab's spelling of the visitor a menu title names. The title is expected to be the bare
     * name; if it carries more, the tab name it contains is the key, so the offer is not pruned the
     * next second for matching nobody.
     */
    private String resolveName(String title) {
        List<String> tab = waiting;
        if (tab == null) {
            return title;
        }
        String lower = VisitorShoppingList.key(title);
        for (String name : tab) {
            if (VisitorShoppingList.key(name).equals(lower)) {
                return name;
            }
        }
        for (String name : tab) {
            if (!name.isEmpty() && lower.contains(VisitorShoppingList.key(name))) {
                return name;
            }
        }
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Visitor] menu title '{}' names no visitor in the tab {}",
                title, tab);
        return title;
    }

    /** Every chat line (colour-stripped): the accept line drops that visitor at once. */
    public void onChat(String text) {
        String served = VisitorShoppingList.servedVisitor(text);
        if (served != null && enabled()) {
            remove(served);
        }
    }

    // ------------------------------------------------------------------ reads

    /** The known offers, in the order they were first seen. */
    public synchronized List<Offer> offers() {
        if (!ready()) {
            return List.of();
        }
        List<Offer> out = new ArrayList<>(data.offers.size());
        for (Saved saved : data.offers.values()) {
            List<Required> items = new ArrayList<>(saved.names.size());
            for (int i = 0; i < saved.names.size() && i < saved.amounts.size(); i++) {
                items.add(new Required(saved.names.get(i), saved.amounts.get(i)));
            }
            out.add(new Offer(saved.visitor, items, saved.copper, saved.rareReward));
        }
        return out;
    }

    /** Every visitor the tab lists right now; empty when the widget is not visible. */
    public List<String> tabVisitors() {
        List<String> tab = waiting;
        return tab == null ? List.of() : List.copyOf(tab);
    }

    /** The known offers keyed by {@link VisitorShoppingList#key} - the highlight's lookup. */
    public synchronized Map<String, Offer> offersByKey() {
        Map<String, Offer> out = new LinkedHashMap<>();
        for (Offer offer : offers()) {
            out.put(VisitorShoppingList.key(offer.visitor()), offer);
        }
        return out;
    }

    /** The visitors the tab lists whose menu has not been opened yet; empty when the tab is unknown. */
    public synchronized List<String> unknownVisitors() {
        List<String> tab = waiting;
        if (tab == null || !ready()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String name : tab) {
            if (!data.offers.containsKey(VisitorShoppingList.key(name))) {
                out.add(name);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ writes

    private synchronized void record(Offer offer) {
        if (!ready() || offer.visitor().isEmpty()) {
            return;
        }
        String key = VisitorShoppingList.key(offer.visitor());
        Saved saved = new Saved();
        saved.visitor = offer.visitor();
        for (Required item : offer.items()) {
            saved.names.add(item.name());
            saved.amounts.add(item.amount());
        }
        saved.copper = offer.copper();
        saved.rareReward = offer.rareReward();
        saved.seenAt = System.currentTimeMillis();
        Saved before = data.offers.get(key);
        data.offers.put(key, saved);
        if (before == null || !before.names.equals(saved.names) || !before.amounts.equals(saved.amounts)
                || before.copper != saved.copper) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Visitor] offer recorded: '{}' wants {} for {} copper{}",
                    offer.visitor(), offer.items(), offer.copper(),
                    offer.rareReward() == null ? "" : " + " + offer.rareReward());
            generation++;
            save();
        }
    }

    private synchronized void remove(String visitor) {
        if (ready() && data.offers.remove(VisitorShoppingList.key(visitor)) != null) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Visitor] '{}' served - dropped from the list", visitor);
            generation++;
            save();
        }
    }

    private synchronized void pruneTo(List<String> tab) {
        if (!ready()) {
            return;
        }
        Map<String, Offer> view = new LinkedHashMap<>();
        for (var entry : data.offers.entrySet()) {
            view.put(entry.getKey(), null);
        }
        if (VisitorShoppingList.prune(view, tab)) {
            data.offers.keySet().retainAll(view.keySet());
            generation++;
            save();
        }
    }

    /** Drops every recorded offer for this profile. */
    public synchronized void clear() {
        if (ready() && !data.offers.isEmpty()) {
            data.offers.clear();
            generation++;
            save();
        }
    }

    // ------------------------------------------------------------------ persistence

    private boolean ready() {
        if (!loaded) {
            reloadProfile();
        }
        return loaded && ProfileContext.getInstance().known();
    }

    private Path file() {
        return ProfileContext.getInstance().file(FILE);
    }

    @Override
    public synchronized void reloadProfile() {
        data = new Data();
        unreadable = false;
        loaded = false;
        generation++;
        if (!ProfileContext.getInstance().known()) {
            return;   // the placeholder's path, not this profile's: retry once it is known
        }
        Path path = file();
        if (!Files.isRegularFile(path)) {
            loaded = true;
            return;
        }
        try {
            Data read = SBSFiles.GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), Data.class);
            if (read != null) {
                if (read.offers == null) {
                    read.offers = new LinkedHashMap<>();
                }
                read.offers.values().removeIf(s -> s == null || s.visitor == null
                        || s.names == null || s.amounts == null);
                if (read.schemaVersion > SCHEMA_VERSION) {
                    unreadable = true;   // a newer client's file: use it, never overwrite it
                }
                data = read;
            }
            loaded = true;
        } catch (JsonSyntaxException corrupt) {
            unreadable = true;
            loaded = true;
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Visitor] {} is unreadable ({}) - left as it is",
                    FILE, corrupt.toString());
        } catch (Exception transientFailure) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Visitor] could not read {} yet ({})", FILE,
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
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Visitor] could not write {}: {}", FILE, e.toString());
        }
    }
}
