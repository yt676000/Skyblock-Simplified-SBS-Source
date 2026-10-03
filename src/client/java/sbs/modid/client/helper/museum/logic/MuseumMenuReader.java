/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.museum.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.item.SkyblockItem;
import sbs.modid.client.core.util.PlainText;
import sbs.modid.client.economy.prices.ItemPriceKey;
import sbs.modid.client.helper.museum.model.MuseumCatalog;
import sbs.modid.client.helper.museum.model.MuseumCatalog.Category;
import sbs.modid.client.ui.render.MenuFrame;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the Museum's category pages - {@code (1/9) Museum ➜ Combat} - and records what each shows as
 * donated. Read-only: it looks at the slots and never clicks.
 *
 * <p><b>What "donated" looks like</b> ({@code CONFIRMED} from a capture of all nine Combat pages,
 * 2026-09-06): the slot holds the item's own stack, with its SkyBlock id, and the lore line
 * {@code Donated: Oct 17, 2023}. A donated armor set is shown as its helmet, named
 * {@code "<Set> Armor"}. The name picks the set when the helmet belongs to two.
 *
 * <p><b>What "not donated" looks like is unknown</b>: that capture stored those slots empty. So
 * nothing here depends on it - missing is the catalogue minus what is donated, once every page of a
 * category has been seen - and every id-less slot's shape is logged once per page under
 * {@code [SBS][Museum] marker}, so the first visit in game records it.
 */
public final class MuseumMenuReader {

    private static final MuseumMenuReader INSTANCE = new MuseumMenuReader();

    /** Normalised (lower-case) title: optional "(page/total) ", then "museum ➜ <category>". */
    static final Pattern TITLE =
            Pattern.compile("^(?:\\((\\d+)/(\\d+)\\)\\s+)?museum\\s+➜\\s+([a-z ]+)$");
    private static final String DONATED = "Donated:";

    private AbstractContainerScreen<?> lastScreen;
    private int lastState = -1;
    /** Titles already reported as museum-like but unmatched, so each is logged once. */
    private final Set<String> reportedTitles = new HashSet<>();
    /** Pages whose id-less slot shapes were already logged this session. */
    private final Set<String> loggedMarkers = new HashSet<>();

    private MuseumMenuReader() {
    }

    public static MuseumMenuReader getInstance() {
        return INSTANCE;
    }

    /** Game tick: re-reads the open museum page whenever the server changes its contents. */
    public void tick(Minecraft minecraft) {
        if (!ConfigManager.getInstance().get().museumHelper.enabled
                || !(sbs.modid.client.core.api.GuiStateManager.getInstance().getCurrentScreen()
                        instanceof AbstractContainerScreen<?> screen)) {
            lastScreen = null;
            return;
        }
        int state = screen.getMenu().getStateId();
        if (screen == lastScreen && state == lastState) {
            return;   // nothing changed since the last read: one comparison per tick
        }
        String title = MenuFrame.of(screen).normalised();
        if (!title.contains("museum")) {
            lastScreen = screen;
            lastState = state;
            return;
        }
        Matcher matcher = TITLE.matcher(title);
        Category category = matcher.matches() ? Category.byLabel(matcher.group(3)) : null;
        if (category == null) {
            if (reportedTitles.add(title)) {
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Museum] museum menu not read: \"{}\"", title);
            }
            lastScreen = screen;
            lastState = state;
            return;
        }
        MuseumCatalog catalog = MuseumCatalog.current();
        if (catalog.isEmpty()) {
            return;   // the items resource has not loaded yet: try again next tick, record nothing
        }
        lastScreen = screen;
        lastState = state;
        int page = matcher.group(1) == null ? 1 : Integer.parseInt(matcher.group(1));
        int total = matcher.group(2) == null ? 1 : Integer.parseInt(matcher.group(2));
        read(screen, catalog, category, page, total);
    }

    private void read(AbstractContainerScreen<?> screen, MuseumCatalog catalog, Category category,
                      int page, int total) {
        Set<String> donated = new LinkedHashSet<>();
        boolean logMarkers = loggedMarkers.add(category.name() + ":" + page);
        int unresolved = 0;
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container instanceof Inventory) {
                continue;   // the player's own inventory below the menu
            }
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            String id = SkyblockItem.id(stack);
            List<String> lore = ItemPriceKey.lore(stack);
            boolean isDonated = false;
            for (String line : lore) {
                if (line.startsWith(DONATED)) {
                    isDonated = true;
                    break;
                }
            }
            if (id != null && isDonated) {
                String name = PlainText.strip(stack.getHoverName().getString()).trim();
                String key = catalog.keyForMenuSlot(id, name);
                if (key != null) {
                    donated.add(key);
                } else {
                    unresolved++;
                    SkyblockSimplifiedSBS.LOGGER.info("[SBS][Museum] donated slot not matched to a "
                            + "donation: id={} name=\"{}\"", id, name);
                }
            } else if (logMarkers && (id == null || !isDonated)) {
                // The shape nobody has captured yet. Glass panes are the menu's frame, not entries.
                String item = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
                if (!item.endsWith("stained_glass_pane")) {
                    SkyblockSimplifiedSBS.LOGGER.info("[SBS][Museum] marker {} p{} slot {}: item={} "
                                    + "id={} name=\"{}\" lore(last 3)={}", category.label(), page,
                            slot.index, item, id,
                            PlainText.strip(stack.getHoverName().getString()).trim(),
                            lore.subList(Math.max(0, lore.size() - 3), lore.size()));
                }
            }
        }
        MuseumStore.getInstance().recordPage(category, page, total, donated);
        if (logMarkers) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Museum] read {} page {}/{}: {} donated{}",
                    category.label(), page, total, donated.size(),
                    unresolved > 0 ? ", " + unresolved + " unmatched" : "");
        }
    }
}
