/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.museum;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.helper.museum.ui.MissingDonationsScreen;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.List;

/**
 * Museum Helper (Inventory &amp; Items): which items are still missing from your museum - on their
 * tooltip, as a marker in open containers and the Auction House, and as a list sorted by XP per
 * coin.
 *
 * <p>Its own card rather than rows on Item Overlay: that card is already long, and this has a
 * screen, a command and three switches of its own - a player searching for "museum" should find a
 * card with that name. Self-registered via
 * {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 */
public final class MuseumHelperModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public MuseumHelperModule() {
    }

    @Override
    public String id() {
        return "museum_helper";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.INVENTORY_ITEMS;
    }

    @Override
    public String displayName() {
        return "Museum Helper";
    }

    @Override
    public String description() {
        return "Spot items still missing from your museum, and list them cheapest XP first";
    }

    @Override
    public int accentColor() {
        return 0xFF55CCCC;
    }

    private static SBSConfig.MuseumHelperSettings cfg() {
        return ConfigManager.getInstance().get().museumHelper;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Museum Helper", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("Learns what you have donated by reading the Museum's category "
                                + "pages when you open them (it never clicks anything), and then "
                                + "points out the items still missing. A category only counts once "
                                + "you have opened every one of its pages, so nothing claims an "
                                + "item is missing from half a look. Stored per SkyBlock profile. "
                                + "Default: on."),
                SettingRow.label("Visit every page of a Museum category once to fill it in"),
                SettingRow.button("Open Missing Donations", MissingDonationsScreen::open)
                        .describe("Every donation still missing from the categories you have seen, "
                                + "with its XP and price, sortable by XP per coin, XP or price. "
                                + "Armor sets are one line. Same as /sbs museum."),
                SettingRow.toggle("Museum Tooltip Line", () -> cfg().tooltipLine,
                        () -> { cfg().tooltipLine = !cfg().tooltipLine; save(); })
                        .describe("Adds \"Museum: not donated · +N XP\" to the tooltip of any item "
                                + "you have not donated yet - in your inventory, storage, the "
                                + "Auction House or the Bazaar. Says how old the data is when your "
                                + "last museum visit was more than three days ago. Default: on."),
                SettingRow.toggle("Mark Undonated Items", () -> cfg().highlightContainers,
                        () -> { cfg().highlightContainers = !cfg().highlightContainers; save(); })
                        .describe("A small cyan notch in the corner of every item you have not "
                                + "donated, in any open chest, backpack or menu, and your own "
                                + "inventory while one is open. The Auction House has its own "
                                + "switch below. Default: on."),
                SettingRow.toggle("Mark In Auction House", () -> cfg().highlightAuctions,
                        () -> { cfg().highlightAuctions = !cfg().highlightAuctions; save(); })
                        .describe("The same notch on auctions of items you have not donated, while "
                                + "browsing the Auction House. Default: on."),
                SettingRow.label("Prices come from cached market data only - no extra requests"));
    }
}
