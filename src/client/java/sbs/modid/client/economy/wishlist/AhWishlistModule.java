/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.wishlist;

import net.minecraft.client.Minecraft;
import sbs.modid.client.core.alert.AlertChannelRows;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.economy.prices.LbinCache;
import sbs.modid.client.economy.wishlist.ui.WishlistScreen;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.ArrayList;
import java.util.List;

/**
 * The AH Wishlist module, registering itself (see {@link SbsModule}).
 *
 * <p>The player lists the items they want - item, spec, most they will pay - and is told when a
 * matching one is listed as BIN, with a chat line that opens the auction. Matching runs on the
 * auction crawl the mod already does for lowest-BIN prices ({@code WishlistWatcher}); no request
 * of its own and no licence needed.
 *
 * <p><b>Why this is allowed under the "never plays the game" rule:</b> it notifies and nothing else.
 * Opening the auction is the player's click, and buying it is the player's click in Hypixel's own
 * menu. It sees listings once a minute at best, so it is a reminder, not a race.
 */
public final class AhWishlistModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public AhWishlistModule() {
    }

    @Override
    public String id() {
        return "ah_wishlist";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.ECONOMY;
    }

    @Override
    public String displayName() {
        return "AH Wishlist";
    }

    @Override
    public String description() {
        return "Your wanted items with spec and max price - alert when one is listed";
    }

    @Override
    public int accentColor() {
        return 0xFFFFC94D;
    }

    private static SBSConfig.AhWishlistSettings cfg() {
        return ConfigManager.getInstance().get().ahWishlist;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        List<SettingRow> rows = new ArrayList<>();
        rows.add(SettingRow.toggle("AH Wishlist", () -> cfg().enabled,
                        () -> {
                            cfg().enabled = !cfg().enabled;
                            save();
                            if (cfg().enabled) {
                                LbinCache.getInstance().requestRefresh();
                            }
                        })
                .describe("Watches the Auction House for the items on your wishlist and tells you when "
                        + "one is listed as Buy It Now at or under your price with at least the spec you "
                        + "asked for. Checks about once a minute. Off by default."));
        rows.add(SettingRow.button("Edit Wishlist", () -> Minecraft.getInstance().setScreenAndShow(
                        new WishlistScreen(sbs.modid.client.core.api.ScreenAccess.current())))
                .describe("Add, change and remove entries: item, max price, minimum enchants, reforge, "
                        + "stars, potato books, recombobulator, gem slots and gems, plus a blacklist for "
                        + "skins, dyes, reforges, enchants or gems you do not want. Every value is a "
                        + "minimum - an item with more still matches."));
        rows.add(SettingRow.label("Hold an item and use \"From Held Item\" to copy its spec"));
        rows.addAll(AlertChannelRows.forAlert("ah_wishlist_match", "a wishlist item is listed",
                () -> cfg().alertChannels,
                mask -> { cfg().alertChannels = mask; save(); }));
        rows.add(SettingRow.label("The chat line carries the click that opens the auction"));
        return rows;
    }
}
