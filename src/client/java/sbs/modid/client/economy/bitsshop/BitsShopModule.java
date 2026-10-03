/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.bitsshop;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.List;

/**
 * Bits Shop Helper (Economy): works out what every bits offer is worth per bit from the live auction
 * and Bazaar data, boxes the best two, and puts the number in each offer's tooltip.
 * Self-registered via {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 */
public final class BitsShopModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public BitsShopModule() {
    }

    @Override
    public String id() {
        return "bits_shop";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.ECONOMY;
    }

    @Override
    public String displayName() {
        return "Bits Shop Helper";
    }

    @Override
    public String description() {
        return "Coins per Bit on every offer, with the best deals in the shop boxed";
    }

    @Override
    public int accentColor() {
        return 0xFF8FD14D;
    }

    private static SBSConfig.BitsShopSettings cfg() {
        return ConfigManager.getInstance().get().bitsShop;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Bits Shop Helper", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("Works out what each Bits Shop offer is worth per bit - the "
                                + "item's live sell price divided by its bits cost - and ranks "
                                + "every offer in the shop against the others. Nothing is read, "
                                + "stored or drawn anywhere except on a bits shop page."),
                SettingRow.label("§8Prices come from the auction / Bazaar data already cached"),

                SettingRow.toggle("Highlight Best Deals", () -> cfg().highlightBest,
                        () -> { cfg().highlightBest = !cfg().highlightBest; save(); })
                        .describe("Boxes the best coins-per-bit offer in green and the runner-up "
                                + "in yellow. The ranking is shop-wide, not page-wide, so most "
                                + "pages show no box - that is the answer, not a failure: the best "
                                + "deal is on another page. Every offer still carries its own "
                                + "number and rank in the tooltip."),
                SettingRow.toggle("Coins Per Bit In Tooltip", () -> cfg().showTooltipLine,
                        () -> { cfg().showTooltipLine = !cfg().showTooltipLine; save(); })
                        .describe("Adds the coins-per-bit figure, what the item sells for, and "
                                + "where the offer ranks, to the bottom of its tooltip - plus "
                                + "where to find the current best deal."),

                SettingRow.toggle("Learn Offers While Browsing", () -> cfg().learnOffers,
                        () -> { cfg().learnOffers = !cfg().learnOffers; save(); })
                        .describe("Remembers each offer as you open its category. This is what "
                                + "makes a shop-wide comparison possible at all: the items in the "
                                + "sub-menus you are not looking at have to come from somewhere. "
                                + "Bits prices are read from Hypixel's own tooltip, so what is "
                                + "remembered is always what the game said, and a changed price "
                                + "corrects itself the next time you open that page."),
                SettingRow.label(catalogSummary()),
                SettingRow.label("§8Open each category once and the whole shop is comparable"),

                SettingRow.button("Forget Learned Offers", () -> {
                    BitsShopCatalog.getInstance().clear();
                    save();
                })
                        .describe("Empties the remembered shop catalogue. Only worth doing if "
                                + "Hypixel reworks the shop and you would rather re-learn it from "
                                + "scratch than let the old entries age out as you browse."));
    }

    /** How much of the shop is actually known - the number that decides how useful the rank is. */
    private static String catalogSummary() {
        int observed = BitsShopCatalog.getInstance().observedCount();
        int priced = BitsShop.ranking().size();
        if (observed == 0) {
            return "§8No offers seen yet - open the Bits Shop to start";
        }
        return "§8" + observed + " offer(s) seen, " + priced + " with a market price";
    }
}
