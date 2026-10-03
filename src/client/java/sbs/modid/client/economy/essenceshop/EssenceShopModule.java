/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.essenceshop;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.economy.essenceshop.logic.EssencePerkData;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.List;

/**
 * Essence Shop Overview (Economy): beside any essence shop, what each perk still costs to max, what
 * the whole shop costs together, and how far short the player is.
 * Self-registered via {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 */
public final class EssenceShopModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public EssenceShopModule() {
    }

    @Override
    public String id() {
        return "essence_shop";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.ECONOMY;
    }

    @Override
    public String displayName() {
        return "Essence Shop Overview";
    }

    @Override
    public String description() {
        return "Cost to max every perk in the open essence shop, and what you are short";
    }

    @Override
    public int accentColor() {
        return 0xFFB388E0;
    }

    private static SBSConfig.EssenceShopSettings cfg() {
        return ConfigManager.getInstance().get().essenceShop;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Essence Shop Overview", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("A panel beside any essence shop listing what every perk still "
                                + "costs to reach its top level, and the total for the whole shop "
                                + "with maxed perks left out. The shop itself only ever states the "
                                + "price of the next level, so this is not something the menu can "
                                + "be read for. On by default; nothing is drawn anywhere else."),
                SettingRow.label(tableSummary()),
                SettingRow.label("§8Drag the panel by its header; the '-' collapses it"),

                SettingRow.toggle("Coin Estimate", () -> cfg().coinEstimate,
                        () -> { cfg().coinEstimate = !cfg().coinEstimate; save(); })
                        .describe("Adds what the missing essence would cost in coins, priced at the "
                                + "Bazaar's instant-buy side - what acquiring it actually costs, "
                                + "not what selling it would fetch. An estimate: the price moves, "
                                + "and a large order moves it further. On by default."),
                SettingRow.toggle("Bazaar Button", () -> cfg().bazaarShortcut,
                        () -> { cfg().bazaarShortcut = !cfg().bazaarShortcut; save(); })
                        .describe("Shows a button that opens the Bazaar for the essence this shop "
                                + "spends. It runs the search command once, on the click, and "
                                + "closes the menu - nothing is sent automatically and nothing is "
                                + "reopened afterwards. Off means the panel only ever displays. "
                                + "On by default."),
                SettingRow.label("§8Perk clicks are never touched - buying stays entirely yours"));
    }

    /** Whether the cost table is actually here, since without it the panel cannot appear at all. */
    private static String tableSummary() {
        EssencePerkData data = EssencePerkData.getInstance();
        if (!data.available()) {
            return "§8Perk cost table not downloaded yet - the panel appears once it is";
        }
        return "§8Perk cost table: " + data.shopIds().size() + " shop(s) known";
    }
}
